package io.github.fragudev.ailab.rag.internal;

import io.github.fragudev.ailab.rag.RagCitationResult;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * Strips {@code [N]} citation markers out of the streamed token text in real time, and — once the
 * full answer is known — resolves them against {@link ChunkReference}s into real
 * {@link RagCitationResult}s. Citations never reach the client as inline markers
 * (docs/architecture.md #5): {@link #stripDelta} buffers a possible partial marker at a chunk
 * boundary (e.g. a delta ending in {@code "["} or {@code "[1"}) rather than ever forwarding one.
 *
 * <p>One instance per RAG turn — {@code stripDelta} is stateful.
 */
public final class CitationExtractor {

    private static final Pattern MARKER = Pattern.compile("\\[(\\d+)]");

    private final StringBuilder pending = new StringBuilder();

    public String stripDelta(String deltaContent) {
        pending.append(deltaContent);
        Matcher matcher = MARKER.matcher(pending);
        StringBuilder result = new StringBuilder();
        int lastFlushed = 0;
        while (matcher.find()) {
            result.append(pending, lastFlushed, matcher.start());
            lastFlushed = matcher.end();
        }
        String tail = pending.substring(lastFlushed);
        int safeBoundary = safeFlushBoundary(tail);
        result.append(tail, 0, safeBoundary);
        pending.delete(0, lastFlushed + safeBoundary);
        return result.toString();
    }

    /** Whatever's left unflushed at stream end wasn't a real marker after all (or the stream ended
     * mid-marker) — forward it as plain text rather than silently dropping it. */
    public String flushRemaining() {
        String leftover = pending.toString();
        pending.setLength(0);
        return leftover;
    }

    public static List<RagCitationResult> extractCitations(
            String answerText, Map<Integer, ChunkReference> referencesByMarker) {
        Matcher matcher = MARKER.matcher(answerText);
        Set<Integer> seen = new LinkedHashSet<>();
        List<RagCitationResult> citations = new ArrayList<>();
        while (matcher.find()) {
            int marker = Integer.parseInt(matcher.group(1));
            ChunkReference reference = referencesByMarker.get(marker);
            if (reference != null && seen.add(marker)) {
                citations.add(new RagCitationResult(
                        marker,
                        reference.documentId(),
                        reference.chunkId(),
                        reference.score(),
                        quotedSentence(answerText, matcher.start())));
            }
        }
        return citations;
    }

    public static String stripAll(String text) {
        return MARKER.matcher(text).replaceAll("");
    }

    /** A partial marker can only start at a trailing {@code '['} followed by digits only; anything
     * else in the tail is definitely not going to become {@code [N]} and is safe to flush. */
    private static int safeFlushBoundary(String tail) {
        int bracketIndex = tail.lastIndexOf('[');
        if (bracketIndex == -1) {
            return tail.length();
        }
        String afterBracket = tail.substring(bracketIndex + 1);
        boolean stillPossibleMarker =
                afterBracket.isEmpty() || afterBracket.chars().allMatch(Character::isDigit);
        return stillPossibleMarker ? bracketIndex : tail.length();
    }

    private static @Nullable String quotedSentence(String text, int markerStart) {
        int start = 0;
        for (int i = markerStart - 1; i >= 0; i--) {
            char c = text.charAt(i);
            if (c == '.' || c == '!' || c == '?') {
                start = i + 1;
                break;
            }
        }
        String sentence = text.substring(start, markerStart).trim();
        return sentence.isEmpty() ? null : sentence;
    }
}
