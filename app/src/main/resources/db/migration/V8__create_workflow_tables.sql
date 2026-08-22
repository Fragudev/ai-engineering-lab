-- One row per agentic workflow run and one row per stage of that run (docs/architecture.md #4, #7,
-- Phase 6). Stage-level, not sub-task-level, persistence granularity: a fan-out stage's individual
-- sub-task results (e.g. one retrieval per sub-query) live inside that one step's output JSON, not
-- as separate rows (docs/adr/0010-agent-orchestration.md).
CREATE TABLE workflow_run (
    id UUID PRIMARY KEY,
    type TEXT NOT NULL,
    status TEXT NOT NULL,
    input JSONB NOT NULL,
    output JSONB,
    correlation_id UUID NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL
);

-- Drives resumability: on startup, every run left PENDING or RUNNING by an interrupted process is
-- looked up here and re-driven from its last completed step.
CREATE INDEX idx_workflow_run_status ON workflow_run (status);

CREATE TABLE workflow_step (
    id UUID PRIMARY KEY,
    run_id UUID NOT NULL REFERENCES workflow_run (id) ON DELETE CASCADE,
    step_index INT NOT NULL,
    name TEXT NOT NULL,
    status TEXT NOT NULL,
    input JSONB,
    output JSONB,
    attempts INT NOT NULL DEFAULT 0,
    cost_usd NUMERIC(12, 6),
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL
);

CREATE INDEX idx_workflow_step_run_id ON workflow_step (run_id);
