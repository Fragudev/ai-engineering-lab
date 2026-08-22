package io.github.fragudev.ailab.evaluation.internal;

import io.github.fragudev.ailab.rag.RagCitationResult;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/** Pure functions over an answer's resolved citations — see docs/ai-evaluation.md §3. Precision is
 * {@link Double#NaN} when nothing was cited (undefined, not zero — there's nothing to be wrong
 * about); recall is {@link Double#NaN} when there's no gold set. */
public final class CitationMetrics {

    private CitationMetrics() {}

    /** Share of cited chunks that are gold — "the model cites sources that do not support the claim"
     * when this is low. */
    public static double precision(List<RagCitationResult> citations, Set<UUID> goldChunkIds) {
        if (citations.isEmpty()) {
            return Double.NaN;
        }
        Set<UUID> cited = citations.stream().map(RagCitationResult::chunkId).collect(Collectors.toSet());
        long correct = cited.stream().filter(goldChunkIds::contains).count();
        return (double) correct / cited.size();
    }

    /** Share of gold chunks actually cited — "answer correct but under-attributed" when this is low
     * while the answer itself is otherwise fine. */
    public static double recall(List<RagCitationResult> citations, Set<UUID> goldChunkIds) {
        if (goldChunkIds.isEmpty()) {
            return Double.NaN;
        }
        Set<UUID> cited = citations.stream().map(RagCitationResult::chunkId).collect(Collectors.toSet());
        long found = goldChunkIds.stream().filter(cited::contains).count();
        return (double) found / goldChunkIds.size();
    }
}
