package io.github.fragudev.ailab.aiprovider.internal;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import org.jspecify.annotations.Nullable;

/** Deserialized shape of a chat fixture file under {@code fixtures/chat/}. */
record FixtureSet(@JsonProperty("default") FixtureCase defaultCase, List<FixtureCase> cases) {

    /** @param followUp the response to replay for the SECOND model call in a tool-calling round
     *     trip — a request whose last message is {@code TOOL}-role (the tool's result appended by
     *     {@code tools.ToolCallingChatService}) re-matches this same fixture by its (unchanged)
     *     last {@code USER} message, so without this, the same {@code response} (the original
     *     tool-call JSON) would match forever instead of producing a follow-up answer. {@code null}
     *     for fixtures never used to exercise the tool-calling fallback (see Phase 5's
     *     docs/roadmap.md scope notes). */
    record FixtureCase(
            String matchContains,
            String response,
            int promptTokens,
            int completionTokens,
            @Nullable FollowUp followUp) {}

    record FollowUp(String response, int promptTokens, int completionTokens) {}
}
