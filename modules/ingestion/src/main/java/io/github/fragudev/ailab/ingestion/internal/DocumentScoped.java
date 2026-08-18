package io.github.fragudev.ailab.ingestion.internal;

import java.util.UUID;

/** Lets a single, generic recoverer find the failing document regardless of which stage failed. */
interface DocumentScoped {

    UUID documentId();
}
