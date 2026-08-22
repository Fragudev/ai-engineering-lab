package io.github.fragudev.ailab.tools.internal;

import io.github.fragudev.ailab.aiprovider.ChatChunk;
import reactor.core.publisher.Flux;

/**
 * Peeks only the first non-empty delta of a model turn: if it starts with {@code '{'}, the whole
 * response is buffered (no delta chunks forwarded) so {@link ToolCallingChatService} can attempt to
 * parse it as a tool-call envelope once the terminal chunk arrives; otherwise every chunk streams
 * through live, unmodified. This is what keeps an ordinary answer streaming token-by-token instead
 * of always buffering the whole response.
 *
 * <p>Known limitation, accepted rather than solved: a legitimate answer that happens to start with
 * {@code '{'} is misdetected as a tool-call attempt (docs/adr/0009-tool-design-and-security-boundaries.md).
 */
public final class ToolCallSniffer {

    private ToolCallSniffer() {}

    public static Flux<ChatChunk> sniff(Flux<ChatChunk> upstream) {
        return upstream.switchOnFirst((signal, flux) -> {
            if (signal.hasValue() && looksLikeToolCallStart(signal.get().deltaContent())) {
                return flux.filter(ChatChunk::last);
            }
            return flux;
        });
    }

    private static boolean looksLikeToolCallStart(String firstDelta) {
        String trimmed = firstDelta.stripLeading();
        return !trimmed.isEmpty() && trimmed.charAt(0) == '{';
    }
}
