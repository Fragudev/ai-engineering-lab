package io.github.fragudev.ailab.rag;

import io.github.fragudev.ailab.knowledge.RerankStrategy;

/**
 * A named, code-defined retrieval configuration (see {@link RagProfiles}) — not user-created config,
 * matching the {@code lmstudio}/{@code recorded} adapter-profile pattern already used in
 * {@code ai-provider}.
 *
 * @param name unique identifier, e.g. {@code "hybrid-rerank"}
 * @param topK final number of chunks fed into the generation context
 * @param candidatesPerRetriever how many each of the vector/lexical retrievers contribute before
 *     fusion
 * @param lexicalEnabled whether the lexical (full-text) retriever runs at all
 * @param rerankStrategy which {@link RerankStrategy} to apply after fusion
 * @param contextTokenBudget rough character/token budget for the assembled context (see
 *     {@code internal.ContextBuilder})
 * @param maxVectorDistance abstention gate: if the best vector-retriever match (across all returned
 *     candidates) is farther than this cosine distance, the pipeline declines to answer rather than
 *     generate from weak context. Calibrated against a real bge-m3 distance distribution
 *     (post-roadmap review B5, issue #29, docs/ai-evaluation.md §8) — an earlier value (0.6) was
 *     inherited from the {@code recorded} profile's hash-seeded near-zero-distance embeddings and
 *     was never checked against a real embedding model until this.
 */
public record RagProfile(
        String name,
        int topK,
        int candidatesPerRetriever,
        boolean lexicalEnabled,
        RerankStrategy rerankStrategy,
        int contextTokenBudget,
        double maxVectorDistance) {}
