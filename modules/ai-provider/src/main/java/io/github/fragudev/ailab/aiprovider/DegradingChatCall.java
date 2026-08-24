package io.github.fragudev.ailab.aiprovider;

import java.math.BigDecimal;
import java.util.function.Consumer;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;

/**
 * The call-the-model-and-degrade shape duplicated across {@code QueryNormalizer}, {@code
 * LlmReranker}, {@code SubQueryPlanner}, {@code SourceExtractor} and {@code LlmJudge} (post-roadmap
 * review issue #36): call {@link ChatProvider#complete}, parse the response, and on either a
 * provider failure or an unusable response fall back to a caller-supplied default rather than
 * failing the caller's own operation over an LLM call that is inherently best-effort.
 *
 * <p>Deliberately does <b>not</b> template the log message — each caller keeps its own exact
 * wording (a provider exception and an unparsable response are different root causes, worth
 * distinguishing in the logs), supplied as two small callbacks rather than a format string. What
 * this centralizes is the control flow and the cost accounting: {@link Outcome#costUsd()} is real —
 * {@link BigDecimal#ZERO} only when the provider call itself never completed, and the response's own
 * {@link ChatResponse#estimatedCostUsd()} whenever it did, even if the parsed result was unusable and
 * the caller's fallback is what gets returned. A call that spent real tokens producing a response
 * this code couldn't use still cost real money; silently reporting {@code ZERO} for it would be a
 * fabricated number, not a fallback (AGENTS.md rule 2).
 *
 * <p>{@code AnswerSynthesiser} — named in issue #36's original six — does not actually use this
 * shape: its own javadoc documents a deliberate choice to let a provider failure propagate to {@code
 * StageRunner}'s retry/compensation handling instead, since there is no sensible fallback for "no
 * answer." Left as-is; this helper is for genuine graceful-degradation call sites only.
 */
public final class DegradingChatCall {

    private DegradingChatCall() {}

    /**
     * @param value the parsed result, or the caller's fallback if the call or the parse degraded
     * @param costUsd the real cost incurred, {@link BigDecimal#ZERO} only if the provider call never
     *     completed
     */
    public record Outcome<T>(T value, BigDecimal costUsd) {}

    /**
     * @param parse turns the response's raw content into {@code T}, or returns {@code null} if the
     *     content is unusable (e.g. empty, or fails a caller-specific structural check) — never
     *     expected to throw; this is for "the model said something, but not something we can use,"
     *     not a parsing bug
     * @param fallback returned (with the real cost, if the call completed) whenever either the call
     *     or the parse degrades
     * @param onProviderFailure called with the exception when {@code chatProvider.complete} itself
     *     throws — the caller's own {@code log.warn(...)} call, e.g. covering a timeout via {@code
     *     ProviderTimeoutException}
     * @param onUnparsableResponse called with the raw response content when the call succeeded but
     *     {@code parse} returned {@code null} — the caller's own {@code log.warn(...)} call
     */
    public static <T> Outcome<T> call(
            ChatProvider chatProvider,
            ChatRequest request,
            Function<String, @Nullable T> parse,
            T fallback,
            Consumer<RuntimeException> onProviderFailure,
            Consumer<String> onUnparsableResponse) {
        ChatResponse response;
        try {
            response = chatProvider.complete(request);
        } catch (RuntimeException e) {
            onProviderFailure.accept(e);
            return new Outcome<>(fallback, BigDecimal.ZERO);
        }

        T parsed = parse.apply(response.content());
        if (parsed == null) {
            onUnparsableResponse.accept(response.content());
            return new Outcome<>(fallback, response.estimatedCostUsd());
        }
        return new Outcome<>(parsed, response.estimatedCostUsd());
    }
}
