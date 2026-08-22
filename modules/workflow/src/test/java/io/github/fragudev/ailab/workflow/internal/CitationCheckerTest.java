package io.github.fragudev.ailab.workflow.internal;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class CitationCheckerTest {

    private final CitationChecker checker = new CitationChecker();

    @Test
    void noMarkersIsValid() {
        assertThat(checker.invalidMarkers("An answer with no citations.", 3)).isEmpty();
    }

    @Test
    void validMarkersAreAccepted() {
        assertThat(checker.invalidMarkers("Claim one [1] and claim two [2].", 2))
                .isEmpty();
    }

    @Test
    void markerBeyondSourceCountIsInvalid() {
        assertThat(checker.invalidMarkers("Claim [3] is out of range.", 2)).containsExactly(3);
    }

    @Test
    void markerZeroIsInvalid() {
        assertThat(checker.invalidMarkers("Weird [0] marker.", 2)).containsExactly(0);
    }
}
