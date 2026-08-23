package io.github.fragudev.ailab.rag;

import io.github.fragudev.ailab.aiprovider.ChatMessage;
import io.github.fragudev.ailab.aiprovider.ChatProvider;
import io.github.fragudev.ailab.aiprovider.ChatResponse;
import io.github.fragudev.ailab.aiprovider.TokenUsage;
import io.github.fragudev.ailab.knowledge.HybridSearchOptions;
import io.github.fragudev.ailab.knowledge.HybridSearchService;
import io.github.fragudev.ailab.knowledge.RerankStrategy;
import io.github.fragudev.ailab.knowledge.SearchResult;
import io.github.fragudev.ailab.rag.internal.CitationExtractor;
import io.github.fragudev.ailab.rag.internal.ContextBuilder;
import io.github.fragudev.ailab.rag.internal.QueryNormalizer;
import io.github.fragudev.ailab.tools.ToolCallOrigin;
import io.github.fragudev.ailab.tools.ToolCallingChatService;
import io.github.fragudev.ailab.tools.ToolChatChunk;
import io.github.fragudev.ailab.tools.ToolRegistry;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
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
    private final ToolCallingChatService toolCallingChatService;
    private final ToolRegistry toolRegistry;
    private final ObservationRegistry observationRegistry;

    public RagPipeline(
            QueryNormalizer queryNormalizer,
            HybridSearchService hybridSearchService,
            ContextBuilder contextBuilder,
            ChatProvider chatProvider,
            ToolCallingChatService toolCallingChatService,
            ToolRegistry toolRegistry,
            ObservationRegistry observationRegistry) {
        this.queryNormalizer = queryNormalizer;
        this.hybridSearchService = hybridSearchService;
        this.contextBuilder = contextBuilder;
        this.chatProvider = chatProvider;
        this.toolCallingChatService = toolCallingChatService;
        this.toolRegistry = toolRegistry;
        this.observationRegistry = observationRegistry;
    }

    /** {@code history} is the turn history exactly as sent to a plain {@code ChatProvider} call —
     * {@code query} is appended as the newest user turn after the retrieved-context system message. */
    public Flux<RagAnswerChunk> answer(List<ChatMessage> history, String query, RagProfile profile) {
        String normalizedQuery = queryNormalizer.normalize(history, query);
        List<SearchResult> results = retrieve(normalizedQuery, profile);

        if (shouldAbstain(results, profile)) {
            RagAnswer insufficientAnswer = new RagAnswer(
                    INSUFFICIENT_CONTEXT_ANSWER,
                    List.of(),
                    List.of(),
                    "none",
                    TokenUsage.zero(),
                    Duration.ZERO,
                    BigDecimal.ZERO);
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

        return toolCallingChatService.stream(
                        chatProvider, augmented, toolRegistry.definitions(), ToolCallOrigin.RAG_CONTEXT, null)
                .concatMap(chunk -> {
                    if (chunk.last()) {
                        return Flux.fromIterable(finalizeAnswer(chunk, context, extractor));
                    }
                    if (chunk.toolCall() != null) {
                        return Flux.just(RagAnswerChunk.toolCall(chunk.toolCall()));
                    }
                    if (chunk.toolResult() != null) {
                        return Flux.just(RagAnswerChunk.toolResult(chunk.toolResult()));
                    }
                    if (chunk.pendingConfirmation() != null) {
                        return Flux.just(RagAnswerChunk.pendingConfirmation(chunk.pendingConfirmation()));
                    }
                    String stripped = extractor.stripDelta(chunk.deltaContent());
                    return stripped.isEmpty() ? Flux.empty() : Flux.just(RagAnswerChunk.delta(stripped));
                });
    }

    /** The debug view: runs retrieval (and reranking) but never calls the model —
     * {@code POST /api/v1/retrieval:search}. */
    public RetrievalTrace search(String query, RagProfile profile) {
        String normalizedQuery = queryNormalizer.normalize(List.of(), query);
        List<SearchResult> results = retrieve(normalizedQuery, profile);
        return new RetrievalTrace(query, normalizedQuery, profile, results);
    }

    /** One {@link Observation} per retrieval, named {@code rag.retrieve} to sit alongside
     * {@code ai-provider}'s {@code gen_ai.chat} in the same trace — attributes named under the
     * project-invented {@code rag.*} namespace, since OTel's GenAI semantic conventions don't cover
     * retrieval (docs/adr/0012-observability-conventions.md). */
    private List<SearchResult> retrieve(String normalizedQuery, RagProfile profile) {
        Observation observation = Observation.createNotStarted("rag.retrieve", observationRegistry)
                .lowCardinalityKeyValue("rag.top_k", String.valueOf(profile.topK()))
                .lowCardinalityKeyValue(
                        "rag.rerank.enabled", String.valueOf(profile.rerankStrategy() != RerankStrategy.NONE));
        return observation.observe(() -> {
            List<SearchResult> results = hybridSearchService.search(normalizedQuery, toSearchOptions(profile));
            observation.highCardinalityKeyValue("rag.retrieved_chunk_count", String.valueOf(results.size()));
            return results;
        });
    }

    private static List<RagAnswerChunk> finalizeAnswer(
            ToolChatChunk lastChunk, ContextBuilder.Context context, CitationExtractor extractor) {
        ChatResponse aggregate = lastChunk.aggregate();
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
                lastChunk.toolInvocations(),
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
