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
 * @param mmrLambda relevance-vs-diversity weight for {@link RerankStrategy#MMR} — MMR picks each next
 *     chunk maximizing {@code λ·relevance − (1−λ)·max_similarity(already_selected)}, so higher λ
 *     favours pure relevance and lower λ favours diversity. Ignored unless {@code rerankStrategy} is
 *     {@code MMR}. Was a hardcoded {@code 0.7} in {@code MmrReranker} until issue #67 measured MMR
 *     losing recall on this narrow corpus and needed it tunable per profile to investigate; the
 *     value here is still "a starting heuristic, not tuned against a golden dataset" (AGENTS.md
 *     rule 2) until that measurement is done.
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
        double mmrLambda,
        int contextTokenBudget,
        double maxVectorDistance) {}
