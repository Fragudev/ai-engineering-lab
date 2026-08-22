-- Evaluation harness (docs/architecture.md #7, Phase 4). eval_case.gold_chunk_refs stores stable
-- "title#ordinal" identifiers (e.g. "pgvector#3"), not raw chunk UUIDs — corpus content isn't
-- committed (corpus/ATTRIBUTION.md), so a golden dataset can't hardcode ids from one particular
-- ingestion run. The eval runner resolves each ref to a real chunk at run time (evaluation.internal
-- .GoldChunkResolver) by looking up document(title) then chunk(document_id, ordinal) — reproducible
-- because Chunker (Phase 2) is a deterministic pure function of the input text.
CREATE TABLE eval_dataset (
    id UUID PRIMARY KEY,
    name TEXT NOT NULL,
    version TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uq_eval_dataset_name_version UNIQUE (name, version)
);

CREATE TABLE eval_case (
    id UUID PRIMARY KEY,
    dataset_id UUID NOT NULL REFERENCES eval_dataset (id) ON DELETE CASCADE,
    case_key TEXT NOT NULL,
    question TEXT NOT NULL,
    expected_answer TEXT NOT NULL,
    gold_chunk_refs TEXT[] NOT NULL,
    tags TEXT[] NOT NULL,
    category TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uq_eval_case_dataset_key UNIQUE (dataset_id, case_key)
);

CREATE INDEX idx_eval_case_dataset_id ON eval_case (dataset_id);

CREATE TABLE eval_run (
    id UUID PRIMARY KEY,
    dataset_id UUID NOT NULL REFERENCES eval_dataset (id),
    rag_profile TEXT NOT NULL,
    model TEXT NOT NULL,
    hardware TEXT,
    started_at TIMESTAMPTZ NOT NULL,
    finished_at TIMESTAMPTZ
);

CREATE TABLE eval_result (
    id UUID PRIMARY KEY,
    run_id UUID NOT NULL REFERENCES eval_run (id) ON DELETE CASCADE,
    case_id UUID NOT NULL REFERENCES eval_case (id),
    answer TEXT NOT NULL,
    metrics JSONB NOT NULL,
    created_at TIMESTAMPTZ NOT NULL
);

CREATE INDEX idx_eval_result_run_id ON eval_result (run_id);
