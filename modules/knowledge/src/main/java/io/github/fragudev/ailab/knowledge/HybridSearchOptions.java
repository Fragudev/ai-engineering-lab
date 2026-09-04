package io.github.fragudev.ailab.knowledge;

/**
 * @param topK final number of chunks to return, after fusion and reranking
 * @param candidatesPerRetriever how many each of the vector/lexical retrievers contribute before
 *     fusion — larger than {@code topK} so fusion and reranking have something to work with
 * @param lexicalEnabled whether the lexical (full-text) retriever runs at all; {@code false} is the
 *     {@code dense-only} profile
 * @param rerankStrategy which {@link RerankStrategy} to apply after fusion
 * @param mmrLambda relevance-vs-diversity weight passed to {@link RerankStrategy#MMR}'s reranker;
 *     ignored for {@link RerankStrategy#NONE} and {@link RerankStrategy#LLM}
 */
public record HybridSearchOptions(
        int topK,
        int candidatesPerRetriever,
        boolean lexicalEnabled,
        RerankStrategy rerankStrategy,
        double mmrLambda) {}
