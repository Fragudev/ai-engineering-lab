package io.github.fragudev.ailab.knowledge.internal;

import io.github.fragudev.ailab.knowledge.SearchResult;
import java.util.ArrayList;
import java.util.List;

public interface Reranker {

    /** {@code candidates} is the full post-filter, pre-rerank set (may exceed {@code topK}); the
     * implementation picks and orders the best {@code topK} from it. */
    List<SearchResult> rerank(float[] queryEmbedding, String queryText, List<SearchResult> candidates, int topK);

    /** Stamps 1-based {@code finalRank} onto an already-ordered list, leaving every other field as-is. */
    static List<SearchResult> assignFinalRank(List<SearchResult> ordered) {
        List<SearchResult> ranked = new ArrayList<>(ordered.size());
        for (int i = 0; i < ordered.size(); i++) {
            SearchResult r = ordered.get(i);
            ranked.add(new SearchResult(
                    r.chunk(), r.vectorDistance(), r.lexicalRank(), r.fusedScore(), r.rerankScore(), i + 1));
        }
        return ranked;
    }
}
