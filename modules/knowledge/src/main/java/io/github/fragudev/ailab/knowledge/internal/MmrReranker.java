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

    /** Relevance-vs-diversity weight — a starting heuristic, not tuned against a golden dataset
     * (AGENTS.md rule 2); Phase 4 is where that tuning happens. */
    private static final double LAMBDA = 0.7;

    @Override
    public List<SearchResult> rerank(
            float[] queryEmbedding, String queryText, List<SearchResult> candidates, int topK) {
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
                double mmrScore = LAMBDA * relevance - (1 - LAMBDA) * maxSimilarityToSelected;
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
