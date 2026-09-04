package io.github.fragudev.ailab.evaluation;

/**
 * How many case runs a profile actually completed against how many were attempted, across every
 * repetition — {@code attempted = casesInDataset × repetitions}.
 *
 * <p>{@code completed} is smaller than {@code attempted} whenever {@code EvalRunner} caught an
 * exception from a case and skipped it: a hung or failing live-model call is the usual cause (the
 * skip is deliberate, so one bad case doesn't discard a whole run's results). Every aggregate metric
 * in the profile's report row is a mean over the <em>completed</em> runs only, so a gap here means
 * that row is a subsample. The harness surfaces it rather than letting a degraded run look like a
 * clean one — the exact failure mode behind issues #65 and #67, where a partial run's numbers were
 * read as if they covered the whole dataset.
 */
public record CaseCoverage(int attempted, int completed) {

    public CaseCoverage {
        if (attempted < 0 || completed < 0 || completed > attempted) {
            throw new IllegalArgumentException(
                    "completed (" + completed + ") must be within [0, attempted (" + attempted + ")]");
        }
    }

    /** Every attempted case run produced a result. */
    public boolean complete() {
        return completed == attempted;
    }

    public int skipped() {
        return attempted - completed;
    }

    /** Completed fraction in {@code [0, 1]}; {@code 1.0} when nothing was attempted (vacuously
     * complete), so callers never divide by zero. */
    public double fraction() {
        return attempted == 0 ? 1.0 : (double) completed / attempted;
    }
}
