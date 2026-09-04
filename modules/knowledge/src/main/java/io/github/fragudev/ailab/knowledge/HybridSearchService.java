package io.github.fragudev.ailab.knowledge;

import io.github.fragudev.ailab.aiprovider.EmbeddingProvider;
import io.github.fragudev.ailab.knowledge.internal.ChunkRepository;
import io.github.fragudev.ailab.knowledge.internal.LexicalCandidateRow;
import io.github.fragudev.ailab.knowledge.internal.LlmReranker;
import io.github.fragudev.ailab.knowledge.internal.MmrReranker;
import io.github.fragudev.ailab.knowledge.internal.ReciprocalRankFusion;
import io.github.fragudev.ailab.knowledge.internal.Reranker;
import io.github.fragudev.ailab.knowledge.internal.VectorCandidateRow;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;

/**
 * Retrieve → rerank (docs/architecture.md #9, stages 3 and 5). Vector and lexical candidates are
 * fetched independently and fused via {@link ReciprocalRankFusion}, then optionally reranked.
 * Deciding whether the results are good enough to answer from at all (the "insufficient context"
 * gate) is the {@code rag} module's job, since fused rank-based scores aren't a meaningful absolute
 * threshold — see docs/adr/0008-rag-pipeline-architecture.md.
 */
@Service
public class HybridSearchService {

    private final ChunkRepository chunkRepository;
    private final EmbeddingProvider embeddingProvider;
    private final MmrReranker mmrReranker;
    private final LlmReranker llmReranker;

    HybridSearchService(
            ChunkRepository chunkRepository,
            EmbeddingProvider embeddingProvider,
            MmrReranker mmrReranker,
            LlmReranker llmReranker) {
        this.chunkRepository = chunkRepository;
        this.embeddingProvider = embeddingProvider;
        this.mmrReranker = mmrReranker;
        this.llmReranker = llmReranker;
    }

    public List<SearchResult> search(String queryText, HybridSearchOptions options) {
        float[] queryEmbedding =
                embeddingProvider.embed(List.of(queryText)).get(0).vector();

        List<VectorCandidateRow> vectorRows =
                chunkRepository.findNearestByEmbedding(queryEmbedding, Limit.of(options.candidatesPerRetriever()));
        List<Chunk> vectorChunks =
                vectorRows.stream().map(VectorCandidateRow::chunk).toList();
        Map<UUID, Double> vectorDistanceById =
                vectorRows.stream().collect(Collectors.toMap(r -> r.chunk().id(), VectorCandidateRow::distance));

        List<Chunk> lexicalChunks = List.of();
        Map<UUID, Double> lexicalRankById = Map.of();
        if (options.lexicalEnabled()) {
            List<LexicalCandidateRow> lexicalRows =
                    chunkRepository.findLexicalMatches(queryText, options.candidatesPerRetriever());
            Map<UUID, Chunk> fetchedById =
                    chunkRepository
                            .findAllById(lexicalRows.stream()
                                    .map(LexicalCandidateRow::getId)
                                    .toList())
                            .stream()
                            .collect(Collectors.toMap(Chunk::id, chunk -> chunk));
            lexicalChunks = lexicalRows.stream()
                    .map(row -> fetchedById.get(row.getId()))
                    .filter(java.util.Objects::nonNull)
                    .toList();
            lexicalRankById = lexicalRows.stream()
                    .collect(Collectors.toMap(LexicalCandidateRow::getId, LexicalCandidateRow::getRank));
        }

        List<ReciprocalRankFusion.Fused> fused =
                ReciprocalRankFusion.fuse(vectorChunks, vectorDistanceById, lexicalChunks, lexicalRankById);

        // Fused RRF scores reflect rank position, not absolute relevance — a chunk can rank first
        // in a fusion of two lists purely because nothing else was retrieved, regardless of how
        // dissimilar it actually is to the query. So there is no meaningful absolute threshold to
        // filter on here; "is this good enough to answer from" is judged on raw vector distance,
        // which the rag module's abstention gate does with the full SearchResult it gets back.
        List<SearchResult> candidates = fused.stream()
                .map(f -> new SearchResult(f.chunk(), f.vectorDistance(), f.lexicalRank(), f.fusedScore(), null, 0))
                .toList();

        return switch (options.rerankStrategy()) {
            case NONE -> Reranker.assignFinalRank(limitTo(candidates, options.topK()));
            case MMR -> mmrReranker.rerank(queryEmbedding, queryText, candidates, options.topK(), options.mmrLambda());
            case LLM -> llmReranker.rerank(queryEmbedding, queryText, candidates, options.topK());
        };
    }

    private static List<SearchResult> limitTo(List<SearchResult> results, int topK) {
        return results.size() <= topK ? results : results.subList(0, topK);
    }
}
