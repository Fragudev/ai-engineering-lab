-- One row per resolved [marker] citation in an assistant reply (docs/architecture.md #7, Phase 3).
-- Denormalized rather than joined against chunk/document at read time: the conversation module
-- doesn't depend on knowledge (docs/architecture.md #3), so score/quoted_span are copied in as they
-- were when the answer was generated. FKs to chunk/document are still real, cross-module FKs at the
-- database level (same precedent as chunk.document_id in V3) — the module boundary is a Java/ArchUnit
-- concern, not a schema one.
CREATE TABLE citation (
    id UUID PRIMARY KEY,
    message_id UUID NOT NULL REFERENCES message (id) ON DELETE CASCADE,
    chunk_id UUID NOT NULL REFERENCES chunk (id),
    document_id UUID NOT NULL REFERENCES document (id),
    score DOUBLE PRECISION NOT NULL,
    quoted_span TEXT,
    ordinal INTEGER NOT NULL,
    created_at TIMESTAMPTZ NOT NULL
);

CREATE INDEX idx_citation_message_id ON citation (message_id, ordinal);
