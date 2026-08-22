package io.github.fragudev.ailab.workflow.internal;

/** Thrown by {@link StageRunner} once a stage has exhausted every retry attempt — the signal
 * {@link DocumentationResearchEngine} catches to compensate the run (mark it {@code FAILED} with a
 * clear reason, rather than proceeding with a partial or fabricated result). */
class StageFailedException extends RuntimeException {

    private final String stageName;

    StageFailedException(String stageName, Throwable cause) {
        super("Stage '%s' failed after exhausting retries".formatted(stageName), cause);
        this.stageName = stageName;
    }

    String stageName() {
        return stageName;
    }
}
