package io.github.fragudev.ailab.rag.internal;

import io.github.fragudev.ailab.aiprovider.ChatMessage;
import io.github.fragudev.ailab.aiprovider.ChatProvider;
import io.github.fragudev.ailab.aiprovider.ChatRequest;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Rewrites a conversational follow-up ("what about its scaling limits") into a standalone question
 * for retrieval, using history. A no-op for the first turn (nothing to fold in) and on any provider
 * failure — falls back to the original query rather than failing the whole answer over a retrieval
 * quality-of-life step (docs/architecture.md #9, stage 1).
 */
@Component
public class QueryNormalizer {

    private static final Logger log = LoggerFactory.getLogger(QueryNormalizer.class);
    private static final String INSTRUCTION =
            "You rewrite conversational follow-up questions into standalone questions for a search "
                    + "engine. Respond with ONLY the rewritten question, no other text.";

    private final ChatProvider chatProvider;

    public QueryNormalizer(ChatProvider chatProvider) {
        this.chatProvider = chatProvider;
    }

    public String normalize(List<ChatMessage> history, String query) {
        if (history.isEmpty()) {
            return query;
        }
        try {
            String rewritten = chatProvider
                    .complete(new ChatRequest(
                            List.of(ChatMessage.system(INSTRUCTION), ChatMessage.user(buildPrompt(history, query)))))
                    .content()
                    .trim();
            return rewritten.isEmpty() ? query : rewritten;
        } catch (RuntimeException e) {
            log.warn("Query normalization failed, using the original query for retrieval", e);
            return query;
        }
    }

    private static String buildPrompt(List<ChatMessage> history, String query) {
        StringBuilder prompt = new StringBuilder("Conversation so far:\n");
        for (ChatMessage message : history) {
            prompt.append(message.role()).append(": ").append(message.content()).append('\n');
        }
        return prompt.append("\nLatest question: ")
                .append(query)
                .append("\n\nRewrite the latest question as a standalone question that doesn't depend on the "
                        + "conversation above.")
                .toString();
    }
}
