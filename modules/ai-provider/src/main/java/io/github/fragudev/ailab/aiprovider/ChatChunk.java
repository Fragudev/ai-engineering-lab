package io.github.fragudev.ailab.aiprovider;

import org.jspecify.annotations.Nullable;

/**
 * One element of a streamed chat turn. {@code aggregate} is {@code null} until the final chunk,
 * which carries the full content, model, token usage, latency and cost — usage is only reliable on
 * the terminal response, not on every intermediate delta.
 */
public record ChatChunk(
        String deltaContent, boolean last, @Nullable ChatResponse aggregate) {

    public static ChatChunk delta(String text) {
        return new ChatChunk(text, false, null);
    }

    public static ChatChunk last(ChatResponse aggregate) {
        return new ChatChunk("", true, aggregate);
    }
}
