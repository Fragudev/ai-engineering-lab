package io.github.fragudev.ailab.workflow.internal;

import io.github.fragudev.ailab.aiprovider.ChatMessage;
import io.github.fragudev.ailab.aiprovider.ChatProvider;
import io.github.fragudev.ailab.aiprovider.ChatRequest;
import io.github.fragudev.ailab.aiprovider.DegradingChatCall;
import java.math.BigDecimal;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * The {@code plan-sub-queries} stage's LLM call. Same house style as {@code
 * rag.internal.QueryNormalizer}/{@code evaluation.internal.LlmJudge}: plain-text-constrained output,
 * a hand-rolled line parser, and a graceful fallback (the original query as the sole sub-query)
 * rather than failing the stage over an unparsable response.
 */
@Component
class SubQueryPlanner {

    private static final Logger log = LoggerFactory.getLogger(SubQueryPlanner.class);
    private static final String INSTRUCTION =
            "You break a research question into up to %d focused sub-questions that together cover it. "
                    + "Respond with ONLY the sub-questions, one per line, no numbering or other text.";

    private final ChatProvider chatProvider;

    SubQueryPlanner(ChatProvider chatProvider) {
        this.chatProvider = chatProvider;
    }

    PlanResult plan(String query, int maxSubQueries) {
        ChatRequest request = new ChatRequest(
                List.of(ChatMessage.system(INSTRUCTION.formatted(maxSubQueries)), ChatMessage.user(query)));

        DegradingChatCall.Outcome<List<String>> outcome = DegradingChatCall.call(
                chatProvider,
                request,
                content -> {
                    List<String> subQueries = content.lines()
                            .map(String::trim)
                            .filter(line -> !line.isBlank())
                            .limit(maxSubQueries)
                            .toList();
                    return subQueries.isEmpty() ? null : subQueries;
                },
                List.of(query),
                e -> log.warn("Sub-query planning failed, falling back to the original query", e),
                content -> log.warn("Sub-query planning returned nothing usable, falling back to the original query"));
        return new PlanResult(outcome.value(), outcome.costUsd());
    }

    record PlanResult(List<String> subQueries, BigDecimal costUsd) {}
}
