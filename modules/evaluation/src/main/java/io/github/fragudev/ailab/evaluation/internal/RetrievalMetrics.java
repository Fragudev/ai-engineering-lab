package io.github.fragudev.ailab.evaluation.internal;

import io.github.fragudev.ailab.knowledge.SearchResult;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Pure functions over a retrieval trace's results — see docs/ai-evaluation.md §3. Both return
 * {@link Double#NaN} when {@code goldChunkIds} is empty (undefined, not zero — there's nothing to
 * measure recall of; unanswerable-tagged cases have no gold chunks and don't contribute here). */
public final class RetrievalMetrics {

    private RetrievalMetrics() {}

    /** Share of gold chunks appearing anywhere in {@code results}. */
    public static double recallAtK(List<SearchResult> results, Set<UUID> goldChunkIds) {
        if (goldChunkIds.isEmpty()) {
            return Double.NaN;
        }
        long found = results.stream()
                .map(result -> result.chunk().id())
                .filter(goldChunkIds::contains)
                .distinct()
                .count();
        return (double) found / goldChunkIds.size();
    }

    /** {@code 1 / rank} of the first gold chunk found, {@code 0} if none were retrieved at all. */
    public static double reciprocalRank(List<SearchResult> results, Set<UUID> goldChunkIds) {
        if (goldChunkIds.isEmpty()) {
            return Double.NaN;
        }
        return results.stream()
                .filter(result -> goldChunkIds.contains(result.chunk().id()))
                .mapToInt(SearchResult::finalRank)
                .min()
                .stream()
                .mapToDouble(rank -> 1.0 / rank)
                .findFirst()
                .orElse(0.0);
    }
}
