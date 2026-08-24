package io.github.fragudev.ailab.workflow.internal;

import io.github.fragudev.ailab.aiprovider.ChatMessage;
import io.github.fragudev.ailab.aiprovider.ChatProvider;
import io.github.fragudev.ailab.aiprovider.ChatRequest;
import io.github.fragudev.ailab.aiprovider.DegradingChatCall;
import io.github.fragudev.ailab.aiprovider.LlmDegradationMetrics;
import java.math.BigDecimal;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * The {@code extract-per-source} stage's per-chunk LLM call. Same graceful-fallback philosophy as
 * {@code knowledge.internal.LlmReranker}: a source whose extraction fails, times out, or turns out
 * irrelevant is dropped ({@code facts() == null}), not fatal to the stage as long as at least one
 * other source survives.
 */
@Component
class SourceExtractor {

    private static final Logger log = LoggerFactory.getLogger(SourceExtractor.class);
    private static final String INSTRUCTION =
            "Extract only the facts in the following passage that are relevant to the question. Respond "
                    + "with ONLY the relevant facts, in plain prose, or the single word NONE if nothing in the "
                    + "passage is relevant.";

    private final ChatProvider chatProvider;
    private final LlmDegradationMetrics degradationMetrics;

    SourceExtractor(ChatProvider chatProvider, LlmDegradationMetrics degradationMetrics) {
        this.chatProvider = chatProvider;
        this.degradationMetrics = degradationMetrics;
    }

    ExtractResult extract(String query, String sourceContent) {
        ChatRequest request = new ChatRequest(List.of(
                ChatMessage.system(INSTRUCTION),
                ChatMessage.user("Question: %s\n\nPassage: %s".formatted(query, sourceContent))));

        DegradingChatCall.Outcome<String> outcome = DegradingChatCall.call(
                chatProvider,
                request,
                content -> {
                    String facts = content.trim();
                    return (facts.isEmpty() || facts.equalsIgnoreCase("NONE")) ? null : facts;
                },
                null,
                e -> {
                    log.warn("Source extraction failed for one source, dropping it", e);
                    degradationMetrics.recordProviderFailure("source-extractor", e);
                },
                // Not a degradation: the prompt explicitly instructs the model to answer NONE when
                // nothing in the passage is relevant, so an empty/NONE result is a correct, expected
                // response, not a failure the counter below should be recording.
                content -> {});
        return new ExtractResult(outcome.value(), outcome.costUsd());
    }

    record ExtractResult(@Nullable String facts, BigDecimal costUsd) {}
}
