package io.github.fragudev.ailab.rag;

import io.github.fragudev.ailab.knowledge.RerankStrategy;
import java.util.List;
import java.util.Optional;

/** The fixed set of named RAG profiles (docs/ai-evaluation.md's own illustrative names), exposed via
 * {@code GET /api/v1/rag/profiles}. */
public final class RagProfiles {

    public static final RagProfile DENSE_ONLY =
            new RagProfile("dense-only", 5, 20, false, RerankStrategy.NONE, 2000, 0.6);

    public static final RagProfile HYBRID = new RagProfile("hybrid", 5, 20, true, RerankStrategy.NONE, 2000, 0.6);

    public static final RagProfile HYBRID_RERANK =
            new RagProfile("hybrid-rerank", 5, 20, true, RerankStrategy.MMR, 2000, 0.6);

    public static final RagProfile HYBRID_RERANK_LLM =
            new RagProfile("hybrid-rerank-llm", 5, 20, true, RerankStrategy.LLM, 2000, 0.6);

    private static final List<RagProfile> ALL = List.of(DENSE_ONLY, HYBRID, HYBRID_RERANK, HYBRID_RERANK_LLM);

    private RagProfiles() {}

    public static List<RagProfile> all() {
        return ALL;
    }

    public static Optional<RagProfile> byName(String name) {
        return ALL.stream().filter(profile -> profile.name().equals(name)).findFirst();
    }
}
