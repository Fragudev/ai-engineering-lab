package io.github.fragudev.ailab.rag;

import io.github.fragudev.ailab.knowledge.RerankStrategy;
import java.util.List;
import java.util.Optional;

/** The fixed set of named RAG profiles (docs/ai-evaluation.md's own illustrative names), exposed via
 * {@code GET /api/v1/rag/profiles}. */
public final class RagProfiles {

    /** MMR relevance-vs-diversity weight — the value {@code MmrReranker} carried as a hardcoded
     * constant before issue #67. Still an untuned starting heuristic (AGENTS.md rule 2); it lives
     * here now, per profile, so a golden-dataset sweep can move it without touching {@code knowledge}.
     * Only {@code hybrid-rerank} applies it — the other three profiles pass it through unused. */
    public static final double DEFAULT_MMR_LAMBDA = 0.7;

    // maxVectorDistance: 0.55, recalibrated against a real bge-m3 distance distribution
    // (post-roadmap review B5, issue #29) — not the 0.6 inherited from the recorded profile's
    // hash-seeded near-zero-distance embeddings. See docs/ai-evaluation.md §8 for the real
    // measurement (28 golden-dataset queries: 0.30-0.47; 4 genuinely off-topic control queries:
    // 0.60-0.69) and docs/adr/0013-rag-abstention-threshold.md for the full reasoning. Identical
    // across all four profiles below because they share candidatesPerRetriever=20/topK=5, so the
    // raw vector-candidate pool shouldAbstain reads from is the same regardless of lexicalEnabled
    // or rerankStrategy — verified live, not assumed.
    public static final RagProfile DENSE_ONLY =
            new RagProfile("dense-only", 5, 20, false, RerankStrategy.NONE, DEFAULT_MMR_LAMBDA, 2000, 0.55);

    public static final RagProfile HYBRID =
            new RagProfile("hybrid", 5, 20, true, RerankStrategy.NONE, DEFAULT_MMR_LAMBDA, 2000, 0.55);

    public static final RagProfile HYBRID_RERANK =
            new RagProfile("hybrid-rerank", 5, 20, true, RerankStrategy.MMR, DEFAULT_MMR_LAMBDA, 2000, 0.55);

    public static final RagProfile HYBRID_RERANK_LLM =
            new RagProfile("hybrid-rerank-llm", 5, 20, true, RerankStrategy.LLM, DEFAULT_MMR_LAMBDA, 2000, 0.55);

    private static final List<RagProfile> ALL = List.of(DENSE_ONLY, HYBRID, HYBRID_RERANK, HYBRID_RERANK_LLM);

    private RagProfiles() {}

    public static List<RagProfile> all() {
        return ALL;
    }

    public static Optional<RagProfile> byName(String name) {
        return ALL.stream().filter(profile -> profile.name().equals(name)).findFirst();
    }
}
