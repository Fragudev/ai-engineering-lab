package io.github.fragudev.ailab.ingestion.internal;

import io.github.fragudev.ailab.ingestion.JobStage;
import io.github.fragudev.ailab.platform.IdempotencyGuard;
import io.github.fragudev.ailab.shared.NonRetryableIngestionException;
import io.micrometer.tracing.Tracer;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Set;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Parsing is scoped to plain text/markdown for this phase — real format parsing (PDF/DOCX via
 * Tika) is a separate, later concern (docs/roadmap.md, Phase 2 scope notes). An unsupported MIME
 * type is the ADR's own example of a non-retryable failure: retrying it three times only delays the
 * inevitable, so it skips straight to the dead-letter topic.
 */
@Component
class ParserListener {

    private static final String CONSUMER_GROUP = "ingestion-parser";
    private static final Set<String> SUPPORTED_MIME_TYPES = Set.of("text/plain", "text/markdown");

    private final IdempotencyGuard idempotencyGuard;
    private final AttemptRecording attemptRecording;
    private final IngestionJobRepository jobRepository;
    private final ApplicationEventPublisher eventPublisher;
    private final Tracer tracer;

    ParserListener(
            IdempotencyGuard idempotencyGuard,
            IngestionJobRepository jobRepository,
            ApplicationEventPublisher eventPublisher,
            Tracer tracer,
            AttemptRecording attemptRecording) {
        this.idempotencyGuard = idempotencyGuard;
        this.attemptRecording = attemptRecording;
        this.jobRepository = jobRepository;
        this.eventPublisher = eventPublisher;
        this.tracer = tracer;
    }

    @KafkaListener(topics = "ingestion.document.uploaded.v1", groupId = CONSUMER_GROUP)
    @Transactional
    void onDocumentUploaded(DocumentUploadedEvent event) {
        if (tracer.currentSpan() != null) {
            tracer.currentSpan().tag("correlationId", event.correlationId().toString());
        }
        if (!idempotencyGuard.isNewEvent(CONSUMER_GROUP, event.eventId())) {
            return;
        }

        // Committed independently of this listener's transaction (issue: attempts always 0), so a
        // failure that rolls this listener back still leaves a truthful retry count behind.
        attemptRecording.recordAttempt(event.documentId());

        if (!SUPPORTED_MIME_TYPES.contains(event.mimeType())) {
            throw new NonRetryableIngestionException("Unsupported MIME type: " + event.mimeType());
        }

        String text = new String(Base64.getDecoder().decode(event.contentBase64()), StandardCharsets.UTF_8);

        jobRepository.findByDocumentId(event.documentId()).ifPresent(job -> job.advanceTo(JobStage.PARSED));

        eventPublisher.publishEvent(DocumentParsedEvent.of(event, text));
    }
}
