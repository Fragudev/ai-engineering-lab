package io.github.fragudev.ailab.knowledge.internal;

import io.github.fragudev.ailab.aiprovider.ChatMessage;
import io.github.fragudev.ailab.aiprovider.ChatProvider;
import io.github.fragudev.ailab.aiprovider.ChatRequest;
import io.github.fragudev.ailab.aiprovider.DegradingChatCall;
import io.github.fragudev.ailab.knowledge.SearchResult;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Listwise LLM reranking: sends the query and numbered candidates to the chat model, asking for a
 * relevance ordering, via the project's existing {@link ChatProvider} abstraction. A real, used-in-
 * practice technique, but it adds latency/cost on every reranked turn and depends on the local model
 * reliably following a plain-text instruction — on any malformed or unparsable response, this falls
 * back to the fused order rather than failing the request (docs/adr/0007-hybrid-retrieval-and-fusion.md).
 */
@Component
public class LlmReranker implements Reranker {

    private static final Logger log = LoggerFactory.getLogger(LlmReranker.class);
    private static final int MAX_CANDIDATE_CHARS = 300;
    private static final String RANKING_INSTRUCTION =
            "You rank retrieved passages by relevance to a question. Respond with ONLY a "
                    + "comma-separated list of the passage numbers, most relevant first. No other text.";

    private final ChatProvider chatProvider;

    LlmReranker(ChatProvider chatProvider) {
        this.chatProvider = chatProvider;
    }

    @Override
    public List<SearchResult> rerank(
            float[] queryEmbedding, String queryText, List<SearchResult> candidates, int topK) {
        if (candidates.isEmpty()) {
            return List.of();
        }

        ChatRequest request = new ChatRequest(
                List.of(ChatMessage.system(RANKING_INSTRUCTION), ChatMessage.user(buildPrompt(queryText, candidates))));
        List<SearchResult> fusedFallback = Reranker.assignFinalRank(limitTo(candidates, topK));

        return DegradingChatCall.call(
                        chatProvider,
                        request,
                        content -> {
                            List<Integer> order = parseOrder(content, candidates.size());
                            if (order == null) {
                                return null;
                            }
                            List<SearchResult> reordered = order.stream()
                                    .map(i -> candidates.get(i - 1))
                                    .limit(topK)
                                    .toList();
                            return Reranker.assignFinalRank(reordered);
                        },
                        fusedFallback,
                        e -> log.warn("LLM reranking failed, falling back to fused order", e),
                        content -> log.warn(
                                "LLM reranking returned an unparsable ordering ('{}'), falling back to fused order",
                                content))
                .value();
    }

    private static String buildPrompt(String queryText, List<SearchResult> candidates) {
        StringBuilder prompt = new StringBuilder("Question: ").append(queryText).append("\n\nPassages:\n");
        for (int i = 0; i < candidates.size(); i++) {
            prompt.append(i + 1)
                    .append(". ")
                    .append(truncate(candidates.get(i).chunk().content()))
                    .append('\n');
        }
        return prompt.toString();
    }

    private static String truncate(String text) {
        return text.length() <= MAX_CANDIDATE_CHARS ? text : text.substring(0, MAX_CANDIDATE_CHARS) + "...";
    }

    private static List<SearchResult> limitTo(List<SearchResult> results, int topK) {
        return results.size() <= topK ? results : results.subList(0, topK);
    }

    /** Parses "3,1,2" into [3,1,2] (1-based candidate indices), requiring every number to be a
     * distinct valid index. Returns {@code null} on anything malformed so the caller can fall back
     * rather than fail the request. */
    private static @Nullable List<Integer> parseOrder(String response, int candidateCount) {
        String[] parts = response.trim().split("[,\\s]+");
        List<Integer> order = new ArrayList<>();
        Set<Integer> seen = new HashSet<>();
        for (String part : parts) {
            if (part.isBlank()) {
                continue;
            }
            int n;
            try {
                n = Integer.parseInt(part.trim());
            } catch (NumberFormatException e) {
                return null;
            }
            if (n < 1 || n > candidateCount || !seen.add(n)) {
                return null;
            }
            order.add(n);
        }
        return order.isEmpty() ? null : order;
    }
}
