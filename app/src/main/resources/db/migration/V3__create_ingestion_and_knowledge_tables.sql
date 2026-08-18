-- Schema per docs/architecture.md #7, Phase 2. Spring Modulith's own event-publication-registry
-- table (the transactional outbox backing) is created separately in V4: with spring.jpa.hibernate.
-- ddl-auto=none (this project is Flyway-only, docs/adr/0001), it does not auto-provision itself.

CREATE TABLE document (
    id UUID PRIMARY KEY,
    source_uri TEXT NOT NULL,
    title TEXT NOT NULL,
    mime_type TEXT NOT NULL,
    content_hash TEXT NOT NULL UNIQUE,
    status TEXT NOT NULL,
    metadata JSONB,
    created_at TIMESTAMPTZ NOT NULL
);

CREATE TABLE ingestion_job (
    id UUID PRIMARY KEY,
    document_id UUID NOT NULL REFERENCES document (id) ON DELETE CASCADE,
    stage TEXT NOT NULL,
    attempts INTEGER NOT NULL DEFAULT 0,
    last_error TEXT,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL
);

CREATE INDEX idx_ingestion_job_document_id ON ingestion_job (document_id);

CREATE TABLE chunk (
    id UUID PRIMARY KEY,
    document_id UUID NOT NULL REFERENCES document (id) ON DELETE CASCADE,
    ordinal INTEGER NOT NULL,
    content TEXT NOT NULL,
    token_count INTEGER NOT NULL,
    metadata JSONB,
    embedding vector(1024) NOT NULL,
    content_tsv tsvector GENERATED ALWAYS AS (to_tsvector('english', content)) STORED,
    created_at TIMESTAMPTZ NOT NULL
);

CREATE INDEX idx_chunk_document_id ON chunk (document_id, ordinal);

-- HNSW: better recall-per-latency than IVFFlat and needs no representative training set, which
-- matters when the index starts empty (docs/adr/0003-persistence-and-vector-store.md).
CREATE INDEX idx_chunk_embedding_hnsw ON chunk USING hnsw (embedding vector_cosine_ops);

-- Full-text search (Phase 3, hybrid retrieval); the column itself exists from this migration
-- because it's Postgres-generated and cheap, even though nothing queries it yet.
CREATE INDEX idx_chunk_content_tsv ON chunk USING gin (content_tsv);

-- Redelivery is a no-op (docs/adr/0005-kafka.md): a unique constraint gives the same guarantee as
-- a composite primary key without JPA composite-key mapping (see ProcessedEvent entity).
CREATE TABLE processed_event (
    id UUID PRIMARY KEY,
    consumer_group TEXT NOT NULL,
    event_id UUID NOT NULL,
    processed_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uq_processed_event_group_event UNIQUE (consumer_group, event_id)
);
