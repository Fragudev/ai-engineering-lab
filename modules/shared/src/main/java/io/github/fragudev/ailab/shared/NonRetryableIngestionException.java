package io.github.fragudev.ailab.shared;

/**
 * An ingestion-stage failure that retrying cannot fix (unsupported MIME type, corrupt file, schema
 * violation). Bypasses retry/backoff and goes straight to the dead-letter topic — retrying a
 * permanently broken document three times only delays the inevitable (docs/adr/0005-kafka.md).
 */
public class NonRetryableIngestionException extends RuntimeException {

    public NonRetryableIngestionException(String message) {
        super(message);
    }

    public NonRetryableIngestionException(String message, Throwable cause) {
        super(message, cause);
    }
}
