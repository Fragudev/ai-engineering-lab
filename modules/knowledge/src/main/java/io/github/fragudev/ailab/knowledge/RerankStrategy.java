package io.github.fragudev.ailab.knowledge;

/**
 * No cross-encoder reranker model is available in this project ({@code scripts/bootstrap.sh} only
 * requires a chat model + {@code bge-m3}) — {@link #MMR} and {@link #LLM} are both real techniques
 * built around what is actually available, not stubs (docs/adr/0007-hybrid-retrieval-and-fusion.md).
 */
public enum RerankStrategy {

    /** No reranking; results stay in fused-score order. */
    NONE,

    /**
     * Maximal Marginal Relevance over embeddings already computed during retrieval — no extra model
     * call, trades pure relevance for reduced redundancy among the selected chunks.
     */
    MMR,

    /** Sends the query and numbered candidates to the chat model, asking for a relevance ordering. */
    LLM
}
