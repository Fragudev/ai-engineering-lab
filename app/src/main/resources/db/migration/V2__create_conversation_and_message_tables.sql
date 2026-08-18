-- Schema per docs/architecture.md #7. estimated_cost_usd is not in that original listing; it's
-- added here because roadmap Phase 1's acceptance criteria explicitly require recording cost per
-- answer, and docs/architecture.md #7 is updated alongside this migration to match.
CREATE TABLE conversation (
    id UUID PRIMARY KEY,
    title TEXT,
    rag_profile TEXT,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL
);

CREATE TABLE message (
    id UUID PRIMARY KEY,
    conversation_id UUID NOT NULL REFERENCES conversation (id) ON DELETE CASCADE,
    role TEXT NOT NULL,
    content TEXT NOT NULL,
    model TEXT,
    prompt_tokens INTEGER,
    completion_tokens INTEGER,
    latency_ms BIGINT,
    estimated_cost_usd NUMERIC(12, 6),
    created_at TIMESTAMPTZ NOT NULL
);

CREATE INDEX idx_message_conversation_id ON message (conversation_id, created_at);
