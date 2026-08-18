package io.github.fragudev.ailab.ingestion.internal;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Fixed-size, paragraph-aware chunking (docs/adr/0006-chunking-strategy.md): paragraphs are packed
 * greedily up to a character budget, never split mid-word except for the rare paragraph that alone
 * exceeds the budget. No semantic chunking — that's a different, more advanced technique out of
 * scope for this phase.
 */
final class Chunker {

    /** ~500 tokens at the commonly-cited ~4 chars/token English-text rule of thumb. */
    static final int MAX_CHUNK_CHARS = 2000;

    private Chunker() {}

    static List<ChunkDraft> chunk(String text) {
        List<String> paragraphs = Arrays.stream(text.split("\\n\\s*\\n"))
                .map(String::strip)
                .filter(paragraph -> !paragraph.isEmpty())
                .toList();

        List<ChunkDraft> chunks = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        int ordinal = 0;

        for (String paragraph : paragraphs) {
            if (!current.isEmpty() && current.length() + paragraph.length() + 2 > MAX_CHUNK_CHARS) {
                chunks.add(new ChunkDraft(ordinal++, current.toString().strip()));
                current.setLength(0);
            }

            if (paragraph.length() > MAX_CHUNK_CHARS) {
                for (String piece : splitAtWordBoundaries(paragraph)) {
                    chunks.add(new ChunkDraft(ordinal++, piece));
                }
                continue;
            }

            if (!current.isEmpty()) {
                current.append("\n\n");
            }
            current.append(paragraph);
        }

        if (!current.isEmpty()) {
            chunks.add(new ChunkDraft(ordinal, current.toString().strip()));
        }
        return chunks;
    }

    private static List<String> splitAtWordBoundaries(String text) {
        List<String> pieces = new ArrayList<>();
        int start = 0;
        while (start < text.length()) {
            int end = Math.min(start + MAX_CHUNK_CHARS, text.length());
            if (end < text.length()) {
                int lastSpace = text.lastIndexOf(' ', end);
                if (lastSpace > start) {
                    end = lastSpace;
                }
            }
            pieces.add(text.substring(start, end).strip());
            start = end;
        }
        return pieces;
    }
}
