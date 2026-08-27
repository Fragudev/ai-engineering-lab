package io.github.fragudev.ailab.ingestion.internal;

import io.github.fragudev.ailab.ingestion.JobStage;
import io.github.fragudev.ailab.platform.IdempotencyGuard;
import io.github.fragudev.ailab.shared.NonRetryableIngestionException;
import io.micrometer.tracing.Tracer;
import java.util.List;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
class ChunkerListener {

    private static final String CONSUMER_GROUP = "ingestion-chunker";

    private final IdempotencyGuard idempotencyGuard;
    private final AttemptRecording attemptRecording;
    private final IngestionJobRepository jobRepository;
    private final ApplicationEventPublisher eventPublisher;
    private final Tracer tracer;

    ChunkerListener(
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

    @KafkaListener(topics = "ingestion.document.parsed.v1", groupId = CONSUMER_GROUP)
    @Transactional
    void onDocumentParsed(DocumentParsedEvent event) {
        if (tracer.currentSpan() != null) {
            tracer.currentSpan().tag("correlationId", event.correlationId().toString());
        }
        if (!idempotencyGuard.isNewEvent(CONSUMER_GROUP, event.eventId())) {
            return;
        }

        // Committed independently of this listener's transaction (issue: attempts always 0), so a
        // failure that rolls this listener back still leaves a truthful retry count behind.
        attemptRecording.recordAttempt(event.documentId());

        List<ChunkDraft> chunks = Chunker.chunk(event.text());
        if (chunks.isEmpty()) {
            throw new NonRetryableIngestionException("Document produced no chunks: " + event.documentId());
        }

        jobRepository.findByDocumentId(event.documentId()).ifPresent(job -> job.advanceTo(JobStage.CHUNKED));

        eventPublisher.publishEvent(ChunksCreatedEvent.of(event, chunks));
    }
}
