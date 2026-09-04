package io.github.fragudev.ailab.evaluation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class CaseCoverageTest {

    @Test
    void completeWhenEveryAttemptedRunFinished() {
        CaseCoverage coverage = new CaseCoverage(84, 84);

        assertThat(coverage.complete()).isTrue();
        assertThat(coverage.skipped()).isZero();
        assertThat(coverage.fraction()).isEqualTo(1.0);
    }

    @Test
    void reportsTheGapWhenRunsWereSkipped() {
        CaseCoverage coverage = new CaseCoverage(84, 71);

        assertThat(coverage.complete()).isFalse();
        assertThat(coverage.skipped()).isEqualTo(13);
        assertThat(coverage.fraction()).isEqualTo(71.0 / 84.0);
    }

    @Test
    void nothingAttemptedIsVacuouslyCompleteAndDoesNotDivideByZero() {
        CaseCoverage coverage = new CaseCoverage(0, 0);

        assertThat(coverage.complete()).isTrue();
        assertThat(coverage.fraction()).isEqualTo(1.0);
    }

    @Test
    void rejectsMoreCompletedThanAttempted() {
        assertThatThrownBy(() -> new CaseCoverage(10, 11)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsNegativeCounts() {
        assertThatThrownBy(() -> new CaseCoverage(-1, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CaseCoverage(5, -1)).isInstanceOf(IllegalArgumentException.class);
    }
}
