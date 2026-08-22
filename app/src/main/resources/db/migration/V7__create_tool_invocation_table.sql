-- One row per tool invocation (docs/architecture.md #4, #7, Phase 5), whether triggered by the chat
-- tool-calling loop (message_id set, persisted once the owning assistant message exists — same
-- timing as citation.message_id above) or a direct POST /api/v1/tools/{name}:invoke call
-- (message_id null, persisted immediately, no owning conversation turn).
CREATE TABLE tool_invocation (
    id UUID PRIMARY KEY,
    message_id UUID REFERENCES message (id) ON DELETE CASCADE,
    tool_name TEXT NOT NULL,
    arguments JSONB NOT NULL,
    result JSONB,
    outcome TEXT NOT NULL,
    duration_ms BIGINT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL
);

CREATE INDEX idx_tool_invocation_message_id ON tool_invocation (message_id);
CREATE INDEX idx_tool_invocation_tool_name ON tool_invocation (tool_name, created_at);
