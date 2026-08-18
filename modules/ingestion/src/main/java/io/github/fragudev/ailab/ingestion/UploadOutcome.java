package io.github.fragudev.ailab.ingestion;

/**
 * {@code deduplicated}: the content hash already existed, so nothing new was created or published
 * (docs/architecture.md #10 — uploading the same file twice does not reindex it).
 */
public record UploadOutcome(Document document, IngestionJob job, boolean deduplicated) {}
