package io.github.fragudev.ailab.knowledge.internal;

import io.github.fragudev.ailab.knowledge.SearchResult;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Maximal Marginal Relevance: greedily picks the next chunk maximizing
 * {@code λ·relevance − (1−λ)·max_similarity(already_selected)}, using the embeddings already computed
 * during retrieval — no extra model call. Trades pure relevance for reduced redundancy so near-
 * duplicate chunks don't crowd out distinct ones (docs/adr/0007-hybrid-retrieval-and-fusion.md).
 */
@Component
public class MmrReranker implements Reranker {

    /** Relevance-vs-diversity weight used when a caller doesn't supply one — a starting heuristic,
     * not tuned against a golden dataset (AGENTS.md rule 2). The tunable value now lives on
     * {@code RagProfile.mmrLambda} (issue #67); this constant only backs the {@link Reranker}
     * interface's 4-arg method for callers that don't care to choose. */
    static final double DEFAULT_LAMBDA = 0.7;

    @Override
    public List<SearchResult> rerank(
            float[] queryEmbedding, String queryText, List<SearchResult> candidates, int topK) {
        return rerank(queryEmbedding, queryText, candidates, topK, DEFAULT_LAMBDA);
    }

    /**
     * As {@link #rerank(float[], String, List, int)}, with an explicit relevance-vs-diversity weight
     * {@code lambda} in {@code [0, 1]}: the next chunk maximizes
     * {@code lambda·relevance − (1−lambda)·max_similarity(already_selected)}. {@code lambda = 1} is
     * pure relevance (no diversity penalty); {@code lambda = 0} ignores relevance entirely.
     */
    public List<SearchResult> rerank(
            float[] queryEmbedding, String queryText, List<SearchResult> candidates, int topK, double lambda) {
        List<SearchResult> remaining = new ArrayList<>(candidates);
        List<SearchResult> selected = new ArrayList<>();

        while (!remaining.isEmpty() && selected.size() < topK) {
            SearchResult best = null;
            double bestScore = Double.NEGATIVE_INFINITY;
            for (SearchResult candidate : remaining) {
                double relevance =
                        cosineSimilarity(queryEmbedding, candidate.chunk().embedding());
                double maxSimilarityToSelected = selected.stream()
                        .mapToDouble(s -> cosineSimilarity(
                                candidate.chunk().embedding(), s.chunk().embedding()))
                        .max()
                        .orElse(0.0);
                double mmrScore = lambda * relevance - (1 - lambda) * maxSimilarityToSelected;
                if (mmrScore > bestScore) {
                    bestScore = mmrScore;
                    best = candidate;
                }
            }
            selected.add(withRerankScore(best, bestScore));
            remaining.remove(best);
        }
        return Reranker.assignFinalRank(selected);
    }

    private static SearchResult withRerankScore(SearchResult result, double rerankScore) {
        return new SearchResult(
                result.chunk(),
                result.vectorDistance(),
                result.lexicalRank(),
                result.fusedScore(),
                rerankScore,
                result.finalRank());
    }

    static double cosineSimilarity(float[] a, float[] b) {
        double dot = 0;
        double normA = 0;
        double normB = 0;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
            normA += a[i] * a[i];
            normB += b[i] * b[i];
        }
        if (normA == 0 || normB == 0) {
            return 0;
        }
        return dot / (Math.sqrt(normA) * Math.sqrt(normB));
    }
}
