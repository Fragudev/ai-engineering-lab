package io.github.fragudev.ailab.rag;

import io.github.fragudev.ailab.aiprovider.ChatMessage;
import io.github.fragudev.ailab.aiprovider.ChatProvider;
import io.github.fragudev.ailab.aiprovider.ChatRequest;
import io.github.fragudev.ailab.aiprovider.ChatResponse;
import io.github.fragudev.ailab.aiprovider.TokenUsage;
import io.github.fragudev.ailab.knowledge.HybridSearchOptions;
import io.github.fragudev.ailab.knowledge.HybridSearchService;
import io.github.fragudev.ailab.knowledge.SearchResult;
import io.github.fragudev.ailab.rag.internal.CitationExtractor;
import io.github.fragudev.ailab.rag.internal.ContextBuilder;
import io.github.fragudev.ailab.rag.internal.QueryNormalizer;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.OptionalDouble;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

/**
 * Normalize → retrieve → (abstain, or) build context → generate → extract citations
 * (docs/architecture.md #9, stages 1–2 and 6–8; stages 3–5 are {@code knowledge}'s
 * {@link HybridSearchService}). See docs/adr/0008-rag-pipeline-architecture.md.
 */
@Service
public class RagPipeline {

    private static final String SYSTEM_INSTRUCTION =
            "Answer the user's question using the retrieved context provided in the next message. If "
                    + "that context does not contain enough information to answer, say so plainly rather than "
                    + "guessing or relying on outside knowledge.";

    private static final String INSUFFICIENT_CONTEXT_ANSWER =
            "The knowledge base doesn't contain enough information to answer this question.";

    private final QueryNormalizer queryNormalizer;
    private final HybridSearchService hybridSearchService;
    private final ContextBuilder contextBuilder;
    private final ChatProvider chatProvider;

    public RagPipeline(
            QueryNormalizer queryNormalizer,
            HybridSearchService hybridSearchService,
            ContextBuilder contextBuilder,
            ChatProvider chatProvider) {
        this.queryNormalizer = queryNormalizer;
        this.hybridSearchService = hybridSearchService;
        this.contextBuilder = contextBuilder;
        this.chatProvider = chatProvider;
    }

    /** {@code history} is the turn history exactly as sent to a plain {@code ChatProvider} call —
     * {@code query} is appended as the newest user turn after the retrieved-context system message. */
    public Flux<RagAnswerChunk> answer(List<ChatMessage> history, String query, RagProfile profile) {
        String normalizedQuery = queryNormalizer.normalize(history, query);
        List<SearchResult> results = hybridSearchService.search(normalizedQuery, toSearchOptions(profile));

        if (shouldAbstain(results, profile)) {
            RagAnswer insufficientAnswer = new RagAnswer(
                    INSUFFICIENT_CONTEXT_ANSWER, List.of(), "none", TokenUsage.zero(), Duration.ZERO, BigDecimal.ZERO);
            return Flux.just(
                    RagAnswerChunk.delta(INSUFFICIENT_CONTEXT_ANSWER), RagAnswerChunk.last(insufficientAnswer));
        }

        ContextBuilder.Context context = contextBuilder.build(results, profile.contextTokenBudget());
        List<ChatMessage> augmented = new ArrayList<>();
        augmented.add(ChatMessage.system(SYSTEM_INSTRUCTION));
        augmented.add(ChatMessage.system(context.delimitedContext()));
        augmented.addAll(history);
        augmented.add(ChatMessage.user(query));

        CitationExtractor extractor = new CitationExtractor();

        return chatProvider.stream(new ChatRequest(augmented)).concatMap(chunk -> {
            if (chunk.last()) {
                return Flux.fromIterable(finalizeAnswer(chunk.aggregate(), context, extractor));
            }
            String stripped = extractor.stripDelta(chunk.deltaContent());
            return stripped.isEmpty() ? Flux.empty() : Flux.just(RagAnswerChunk.delta(stripped));
        });
    }

    /** The debug view: runs retrieval (and reranking) but never calls the model —
     * {@code POST /api/v1/retrieval:search}. */
    public RetrievalTrace search(String query, RagProfile profile) {
        String normalizedQuery = queryNormalizer.normalize(List.of(), query);
        List<SearchResult> results = hybridSearchService.search(normalizedQuery, toSearchOptions(profile));
        return new RetrievalTrace(query, normalizedQuery, profile, results);
    }

    private static List<RagAnswerChunk> finalizeAnswer(
            ChatResponse aggregate, ContextBuilder.Context context, CitationExtractor extractor) {
        List<RagAnswerChunk> out = new ArrayList<>();
        String leftover = extractor.flushRemaining();
        if (!leftover.isEmpty()) {
            out.add(RagAnswerChunk.delta(leftover));
        }

        List<RagCitationResult> citations =
                CitationExtractor.extractCitations(aggregate.content(), context.referencesByMarker());
        for (RagCitationResult citation : citations) {
            out.add(RagAnswerChunk.citation(citation));
        }

        RagAnswer ragAnswer = new RagAnswer(
                CitationExtractor.stripAll(aggregate.content()),
                citations,
                aggregate.model(),
                aggregate.usage(),
                aggregate.latency(),
                aggregate.estimatedCostUsd());
        out.add(RagAnswerChunk.last(ragAnswer));
        return out;
    }

    private static HybridSearchOptions toSearchOptions(RagProfile profile) {
        return new HybridSearchOptions(
                profile.topK(), profile.candidatesPerRetriever(), profile.lexicalEnabled(), profile.rerankStrategy());
    }

    /** No candidates at all, or the closest vector match found is farther than the profile's
     * tolerance — see {@link RagProfile#maxVectorDistance()} for why this looks at raw vector
     * distance rather than the fused score. */
    private static boolean shouldAbstain(List<SearchResult> results, RagProfile profile) {
        if (results.isEmpty()) {
            return true;
        }
        OptionalDouble bestVectorDistance = results.stream()
                .map(SearchResult::vectorDistance)
                .filter(Objects::nonNull)
                .mapToDouble(Double::doubleValue)
                .min();
        return bestVectorDistance.isPresent() && bestVectorDistance.getAsDouble() > profile.maxVectorDistance();
    }
}
