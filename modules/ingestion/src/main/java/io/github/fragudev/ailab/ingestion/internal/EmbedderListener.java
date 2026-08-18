package io.github.fragudev.ailab.ingestion.internal;

import io.github.fragudev.ailab.aiprovider.Embedding;
import io.github.fragudev.ailab.aiprovider.EmbeddingProvider;
import io.github.fragudev.ailab.ingestion.JobStage;
import io.github.fragudev.ailab.knowledge.Chunk;
import io.github.fragudev.ailab.knowledge.ChunkService;
import io.github.fragudev.ailab.platform.IdempotencyGuard;
import io.micrometer.tracing.Tracer;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
class EmbedderListener {

    private static final String CONSUMER_GROUP = "ingestion-embedder";

    private final IdempotencyGuard idempotencyGuard;
    private final IngestionJobRepository jobRepository;
    private final DocumentRepository documentRepository;
    private final ChunkService chunkService;
    private final EmbeddingProvider embeddingProvider;
    private final ApplicationEventPublisher eventPublisher;
    private final Tracer tracer;

    EmbedderListener(
            IdempotencyGuard idempotencyGuard,
            IngestionJobRepository jobRepository,
            DocumentRepository documentRepository,
            ChunkService chunkService,
            EmbeddingProvider embeddingProvider,
            ApplicationEventPublisher eventPublisher,
            Tracer tracer) {
        this.idempotencyGuard = idempotencyGuard;
        this.jobRepository = jobRepository;
        this.documentRepository = documentRepository;
        this.chunkService = chunkService;
        this.embeddingProvider = embeddingProvider;
        this.eventPublisher = eventPublisher;
        this.tracer = tracer;
    }

    @KafkaListener(topics = "ingestion.chunks.created.v1", groupId = CONSUMER_GROUP)
    @Transactional
    void onChunksCreated(ChunksCreatedEvent event) {
        if (tracer.currentSpan() != null) {
            tracer.currentSpan().tag("correlationId", event.correlationId().toString());
        }
        if (!idempotencyGuard.isNewEvent(CONSUMER_GROUP, event.eventId())) {
            return;
        }

        List<String> texts = event.chunks().stream().map(ChunkDraft::content).toList();
        List<Embedding> embeddings = embeddingProvider.embed(texts);

        List<Chunk> chunks = new ArrayList<>();
        for (int i = 0; i < event.chunks().size(); i++) {
            ChunkDraft draft = event.chunks().get(i);
            chunks.add(new Chunk(
                    UUID.randomUUID(),
                    event.documentId(),
                    draft.ordinal(),
                    draft.content(),
                    estimateTokenCount(draft.content()),
                    null,
                    embeddings.get(i).vector()));
        }
        chunkService.saveAll(chunks);

        jobRepository.findByDocumentId(event.documentId()).ifPresent(job -> job.advanceTo(JobStage.INDEXED));
        documentRepository.findById(event.documentId()).ifPresent(document -> document.markIndexed());

        eventPublisher.publishEvent(DocumentIndexedEvent.of(event, chunks.size()));
    }

    /** A rough ~4-chars-per-token estimate, not a real tokenizer count — never presented as measured. */
    private static int estimateTokenCount(String text) {
        return Math.max(1, text.length() / 4);
    }
}
