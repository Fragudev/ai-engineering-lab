package io.github.fragudev.ailab.ingestion;

import io.github.fragudev.ailab.ingestion.internal.DocumentRepository;
import io.github.fragudev.ailab.ingestion.internal.DocumentUploadedEvent;
import io.github.fragudev.ailab.ingestion.internal.Hashing;
import io.github.fragudev.ailab.ingestion.internal.IngestionJobRepository;
import io.github.fragudev.ailab.knowledge.ChunkService;
import io.github.fragudev.ailab.shared.DocumentId;
import io.github.fragudev.ailab.shared.IngestionJobId;
import io.micrometer.tracing.Tracer;
import java.util.Base64;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The upload endpoint's only real job: dedup by content hash, persist, and publish one event. The
 * pipeline itself (parse/chunk/embed) runs entirely in the Kafka consumers under {@code internal} —
 * see docs/architecture.md #10 for the sequence.
 */
@Service
public class IngestionService {

    private final DocumentRepository documentRepository;
    private final IngestionJobRepository jobRepository;
    private final ChunkService chunkService;
    private final ApplicationEventPublisher eventPublisher;
    private final Tracer tracer;

    public IngestionService(
            DocumentRepository documentRepository,
            IngestionJobRepository jobRepository,
            ChunkService chunkService,
            ApplicationEventPublisher eventPublisher,
            Tracer tracer) {
        this.documentRepository = documentRepository;
        this.jobRepository = jobRepository;
        this.chunkService = chunkService;
        this.eventPublisher = eventPublisher;
        this.tracer = tracer;
    }

    @Transactional
    public UploadOutcome upload(String title, String mimeType, byte[] content) {
        String contentHash = Hashing.sha256Hex(content);

        var existing = documentRepository.findByContentHash(contentHash);
        if (existing.isPresent()) {
            Document document = existing.get();
            IngestionJob job = jobRepository
                    .findByDocumentId(document.id().value())
                    .orElseThrow(() -> new NoSuchElementException("No job for document " + document.id()));
            return new UploadOutcome(document, job, true);
        }

        DocumentId documentId = DocumentId.generate();
        Document document = new Document(documentId, "upload:" + documentId, title, mimeType, contentHash);
        documentRepository.save(document);

        IngestionJob job = new IngestionJob(IngestionJobId.generate(), documentId);
        jobRepository.save(job);

        UUID correlationId = UUID.randomUUID();
        if (tracer.currentSpan() != null) {
            tracer.currentSpan().tag("correlationId", correlationId.toString());
        }
        eventPublisher.publishEvent(DocumentUploadedEvent.of(
                correlationId,
                documentId.value(),
                title,
                mimeType,
                contentHash,
                Base64.getEncoder().encodeToString(content)));

        return new UploadOutcome(document, job, false);
    }

    public Document getDocument(DocumentId id) {
        return documentRepository
                .findById(id.value())
                .orElseThrow(() -> new NoSuchElementException("No document with id " + id));
    }

    public List<Document> listDocuments() {
        return documentRepository.findAll();
    }

    /** Used to resolve a golden-dataset case's "title#ordinal" gold chunk reference against real
     * ingested content — see {@code evaluation.internal.GoldChunkResolver}. {@code scripts/seed.sh}
     * sets a document's title to its {@code corpus/MANIFEST.yml} id, which is what makes this a
     * stable lookup key. */
    public Optional<Document> findByTitle(String title) {
        return documentRepository.findByTitle(title);
    }

    @Transactional
    public void deleteDocument(DocumentId id) {
        chunkService.deleteByDocumentId(id.value());
        documentRepository.deleteById(id.value());
    }

    public IngestionJob getJob(IngestionJobId id) {
        return jobRepository.findById(id.value()).orElseThrow(() -> new NoSuchElementException("No job with id " + id));
    }
}
