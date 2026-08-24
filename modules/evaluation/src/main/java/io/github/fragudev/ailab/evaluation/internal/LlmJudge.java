package io.github.fragudev.ailab.evaluation.internal;

import io.github.fragudev.ailab.aiprovider.ChatMessage;
import io.github.fragudev.ailab.aiprovider.ChatProvider;
import io.github.fragudev.ailab.aiprovider.ChatRequest;
import io.github.fragudev.ailab.aiprovider.DegradingChatCall;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Secondary, model-graded metrics (docs/ai-evaluation.md §3), which states their weaknesses
 * plainly: a local model judging another local model is a weak instrument, judges show
 * self-preference and verbosity bias, and scores aren't comparable across judge models or prompt
 * versions. Same graceful-fallback philosophy as {@code knowledge.internal.LlmReranker}: an
 * unparsable or failed judge call is recorded as absent rather than crashing the run.
 *
 * <p>Under the {@code recorded} profile, a fixture-replay judge grading a fixture-replay answer
 * proves nothing — {@code evaluation.internal.ReportWriter} labels these scores accordingly rather
 * than presenting them as meaningful.
 */
@Component
public class LlmJudge {

    private static final Logger log = LoggerFactory.getLogger(LlmJudge.class);
    private static final Pattern SCORE_PATTERN = Pattern.compile("(\\d+(?:\\.\\d+)?)");

    private final ChatProvider chatProvider;

    public LlmJudge(ChatProvider chatProvider) {
        this.chatProvider = chatProvider;
    }

    /** Both scores are normalized to [0, 1], or {@code null} if the judge call failed or returned
     * something unparsable. */
    public JudgeScore judge(String question, String expectedAnswer, String actualAnswer) {
        Double correctness = score(
                "Rate how correct the ACTUAL answer is compared to the EXPECTED answer, on a scale of 0 to "
                        + "10. Respond with ONLY the number.",
                "Question: %s\n\nExpected answer: %s\n\nActual answer: %s"
                        .formatted(question, expectedAnswer, actualAnswer));
        Double faithfulness = score(
                "Rate how well the ACTUAL answer is supported by the REFERENCE material, on a scale of 0 to "
                        + "10 (10 = every claim is supported, 0 = unsupported or fabricated). Respond with ONLY "
                        + "the number.",
                "Reference material: %s\n\nActual answer: %s".formatted(expectedAnswer, actualAnswer));
        return new JudgeScore(normalize(correctness), normalize(faithfulness));
    }

    private @Nullable Double score(String instruction, String prompt) {
        ChatRequest request = new ChatRequest(List.of(ChatMessage.system(instruction), ChatMessage.user(prompt)));
        return DegradingChatCall.call(
                        chatProvider,
                        request,
                        content -> {
                            Matcher matcher = SCORE_PATTERN.matcher(content);
                            return matcher.find() ? Double.parseDouble(matcher.group(1)) : null;
                        },
                        null,
                        e -> log.warn("LLM judge call failed", e),
                        content -> log.warn("LLM judge returned an unparsable score ('{}')", content))
                .value();
    }

    private static @Nullable Double normalize(@Nullable Double raw) {
        return raw == null ? null : Math.max(0, Math.min(10, raw)) / 10.0;
    }

    public record JudgeScore(
            @Nullable Double correctness, @Nullable Double faithfulness) {}
}
