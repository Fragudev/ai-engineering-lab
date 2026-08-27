package io.github.fragudev.ailab.ingestion.internal;

import io.github.fragudev.ailab.ingestion.IngestionJob;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Increments {@code ingestion_job.attempts} for the stage about to run.
 *
 * <p><b>{@code REQUIRES_NEW} is the whole point.</b> Kafka's {@code DefaultErrorHandler} redelivers a
 * failed record in-process, and the listeners are {@code @Transactional} — so an increment written
 * inside the listener's own transaction is rolled back by the very failure it is trying to count.
 * Committing independently, exactly as {@link FailureRecording} already does for the error message,
 * is what makes the count survive.
 *
 * <p>This existed as a dead method on {@code IngestionJob} for the whole project: nothing ever called
 * {@code recordAttempt()}, so {@code attempts} was always {@code 0} — including in the runbook query
 * docs/operations.md tells an operator to run against a failed job, and in the {@code attempts} field
 * the job-status API declares required (post-roadmap review issue #62's neighbour, found while
 * asserting the dead-letter topic in issue #32).
 *
 * <p>The counter is per stage, not per job: {@code IngestionJob.advanceTo} resets it, which is the
 * semantics the entity already defined and this class now honours — "how many times has the current
 * stage been tried".
 */
@Component
class AttemptRecording {

    private final IngestionJobRepository jobRepository;

    AttemptRecording(IngestionJobRepository jobRepository) {
        this.jobRepository = jobRepository;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    void recordAttempt(UUID documentId) {
        jobRepository.findByDocumentId(documentId).ifPresent(IngestionJob::recordAttempt);
    }
}
