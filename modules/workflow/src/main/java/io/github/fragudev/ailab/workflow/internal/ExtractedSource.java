package io.github.fragudev.ailab.workflow.internal;

import java.util.UUID;

/** One source that survived {@code extract-per-source}, with the {@code [marker]} it's cited by in
 * the synthesised answer — the order sources were extracted in fixes the marker numbering. */
record ExtractedSource(UUID chunkId, UUID documentId, int marker, String facts) {}
