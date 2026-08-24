package io.github.fragudev.ailab;

import io.github.fragudev.ailab.ingestion.IngestionService;
import io.github.fragudev.ailab.ingestion.UploadOutcome;
import io.github.fragudev.ailab.shared.DocumentId;
import io.github.fragudev.ailab.shared.IngestionJobId;
import java.io.IOException;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/v1")
class DocumentController {

    private final IngestionService ingestionService;

    DocumentController(IngestionService ingestionService) {
        this.ingestionService = ingestionService;
    }

    // file.getBytes() loads the whole upload into memory — a deliberate deferral, not an oversight
    // (post-roadmap review S3): spring.servlet.multipart.max-file-size (application.yml) now bounds
    // that read to 10 MB, two orders of magnitude above the real corpus's largest document (~42 KB),
    // so buffering is safe at today's bound. Streaming into IngestionService.upload instead is worth
    // revisiting only if that limit is ever raised significantly — not needed at this one.
    @PostMapping(value = "/documents", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    ResponseEntity<DocumentResponse> upload(
            @RequestParam("file") MultipartFile file, @RequestParam(value = "title", required = false) String title)
            throws IOException {
        String actualTitle = (title != null && !title.isBlank()) ? title : file.getOriginalFilename();
        String mimeType = file.getContentType() != null ? file.getContentType() : "application/octet-stream";

        UploadOutcome outcome = ingestionService.upload(actualTitle, mimeType, file.getBytes());
        DocumentResponse body = DocumentResponse.from(outcome.document());

        if (outcome.deduplicated()) {
            return ResponseEntity.ok(body);
        }
        return ResponseEntity.accepted()
                .location(URI.create("/api/v1/ingestion/jobs/" + outcome.job().id()))
                .body(body);
    }

    @GetMapping("/documents")
    List<DocumentResponse> list() {
        return ingestionService.listDocuments().stream()
                .map(DocumentResponse::from)
                .toList();
    }

    @GetMapping("/documents/{id}")
    DocumentResponse get(@PathVariable UUID id) {
        return DocumentResponse.from(ingestionService.getDocument(DocumentId.of(id)));
    }

    @DeleteMapping("/documents/{id}")
    ResponseEntity<Void> delete(@PathVariable UUID id) {
        ingestionService.deleteDocument(DocumentId.of(id));
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/ingestion/jobs/{id}")
    IngestionJobResponse getJob(@PathVariable UUID id) {
        return IngestionJobResponse.from(ingestionService.getJob(IngestionJobId.of(id)));
    }
}
