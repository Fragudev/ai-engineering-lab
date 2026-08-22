package io.github.fragudev.ailab.workflow.internal;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * The {@code self-check} stage's whole job: verifying that every {@code [n]} marker the synthesised
 * answer cites actually corresponds to a source that survived {@code extract-per-source}. A citation-
 * validity check is a lookup, not a judgment call, so this is deterministic — no second LLM
 * self-critique call, the same reasoning ADR-0008 already used for RAG citations
 * (docs/adr/0010-agent-orchestration.md).
 */
@Component
class CitationChecker {

    private static final Pattern MARKER_PATTERN = Pattern.compile("\\[(\\d+)]");

    /** Marker numbers cited in {@code answer} that don't correspond to a real source (1..sourceCount)
     * — empty when every citation is valid. */
    Set<Integer> invalidMarkers(String answer, int sourceCount) {
        Set<Integer> invalid = new LinkedHashSet<>();
        Matcher matcher = MARKER_PATTERN.matcher(answer);
        while (matcher.find()) {
            int marker = Integer.parseInt(matcher.group(1));
            if (marker < 1 || marker > sourceCount) {
                invalid.add(marker);
            }
        }
        return invalid;
    }
}
