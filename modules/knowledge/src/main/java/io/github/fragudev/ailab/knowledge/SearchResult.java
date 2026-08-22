package io.github.fragudev.ailab.knowledge;

import org.jspecify.annotations.Nullable;

/**
 * One ranked chunk from {@link HybridSearchService#search}, carrying enough of the pipeline's own
 * scoring to answer "why is this here" — the retrieval debug endpoint's whole point
 * (docs/architecture.md #5). {@code vectorDistance}/{@code lexicalRank} are {@code null} when the
 * chunk wasn't found by that retriever; {@code rerankScore} is {@code null} when
 * {@link RerankStrategy#NONE} or {@link RerankStrategy#LLM} was used — an LLM reranker produces an
 * ordering, not a calibrated score, and inventing one would misrepresent it (AGENTS.md rule 2).
 */
public record SearchResult(
        Chunk chunk,
        @Nullable Double vectorDistance,
        @Nullable Double lexicalRank,
        double fusedScore,
        @Nullable Double rerankScore,
        int finalRank) {}
