package io.github.fragudev.ailab.tools;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Post-roadmap review issue #62: raw tool-call protocol JSON reached the user as the answer. Measured
 * over a full 84-case live run against {@code qwen/qwen3.8-27b}: **10 of 84 answers** contained an
 * envelope, by two distinct routes —
 *
 * <ul>
 *   <li>4 had prose in front of the envelope, so parsing the whole content as JSON threw and the tool
 *       call was <b>silently never executed</b>; the model's own search request was dropped and the
 *       raw JSON became the answer;
 *   <li>6 arrived after {@code ai.tools.max-calls-per-turn} was spent, where the loop stops parsing
 *       envelopes but the model — primed by its own envelope-shaped turns in the history — keeps
 *       emitting them.
 * </ul>
 *
 * <p>Every string below is copied verbatim from that run's persisted {@code eval_result.answer}
 * rows, not invented.
 */
class ToolCallEnvelopeLeakTest {

    /** Verbatim from case {@code kafka-ui-max-message-size}. Prose first — the route where the tool
     * call was never executed at all. */
    private static final String PROSE_THEN_ENVELOPE = """
            Let me search the knowledge base for information about the message browser's size limits.

            {"tool_call":{"name":"knowledge-base-search","arguments":{"query":"message browser maximum message size limit fetch size"}}}""";

    @Test
    void anEnvelopePrecededByProseIsStillFound() {
        assertThat(ToolCallingChatService.stripToolCallEnvelopes(PROSE_THEN_ENVELOPE))
                .isEqualTo("Let me search the knowledge base for information about the message browser's size limits.");
    }

    @Test
    void everyEnvelopeIsRemovedWhenTheModelEmitsSeveral() {
        String threeCalls = """
                {"tool_call":{"name":"knowledge-base-search","arguments":{"query":"a"}}}
                {"tool_call":{"name":"knowledge-base-search","arguments":{"query":"b"}}}
                {"tool_call":{"name":"knowledge-base-search","arguments":{"query":"c"}}}
                The retrieved context does not contain that information.""";

        assertThat(ToolCallingChatService.stripToolCallEnvelopes(threeCalls))
                .isEqualTo("The retrieved context does not contain that information.")
                .doesNotContain("tool_call");
    }

    /** The brace-balanced scan must respect string literals: a query value containing braces or an
     * escaped quote cannot be allowed to terminate the envelope early, or the leftover tail would be
     * delivered to the user as text. */
    @Test
    void bracesAndEscapedQuotesInsideArgumentsDoNotEndTheEnvelopeEarly() {
        String tricky =
                "{\"tool_call\":{\"name\":\"calculator\",\"arguments\":{\"expression\":\"{a} \\\"b\\\" {c}\"}}}Answer.";

        assertThat(ToolCallingChatService.stripToolCallEnvelopes(tricky)).isEqualTo("Answer.");
    }

    /** The guard against over-reach. Stripping keys on this project's own {@code "tool_call"} envelope,
     * never on JSON in general — an answer that legitimately shows JSON must survive untouched. */
    @Test
    void ordinaryJsonInAnAnswerIsLeftAlone() {
        String answer = """
                Configure it like this:

                {"hnsw": {"m": 16, "ef_construction": 64}}

                That is the documented default.""";

        assertThat(ToolCallingChatService.stripToolCallEnvelopes(answer)).isEqualTo(answer.strip());
    }

    @Test
    void anAnswerWithNoEnvelopeIsUnchanged() {
        String answer = "pgvector uses the <=> operator for cosine distance.";

        assertThat(ToolCallingChatService.stripToolCallEnvelopes(answer)).isEqualTo(answer);
    }
}
