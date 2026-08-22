package io.github.fragudev.ailab.tools.internal;

import io.github.fragudev.ailab.shared.MessageId;
import io.github.fragudev.ailab.shared.ToolInvocationId;
import io.github.fragudev.ailab.tools.ToolCallOutcome;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.jspecify.annotations.Nullable;

/**
 * A persisted record of one tool call, whether triggered by the chat tool-calling loop
 * ({@code messageId} set, persisted once the owning assistant message exists — same timing as
 * {@code Citation.messageId}) or a direct {@code POST /api/v1/tools/{name}:invoke} call
 * ({@code messageId} null, persisted immediately). docs/architecture.md #4, #7.
 */
@Entity
@Table(name = "tool_invocation")
public class ToolInvocation {

    @Id
    private UUID id;

    @Column(name = "message_id")
    private @Nullable UUID messageId;

    @Column(name = "tool_name")
    private String toolName;

    @Convert(converter = ToolInvocationJsonConverter.class)
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private Map<String, Object> arguments;

    @Convert(converter = ToolInvocationJsonConverter.class)
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private @Nullable Map<String, Object> result;

    @Enumerated(EnumType.STRING)
    private ToolCallOutcome outcome;

    @Column(name = "duration_ms")
    private long durationMs;

    @Column(name = "created_at")
    private Instant createdAt;

    protected ToolInvocation() {
        // JPA
    }

    public ToolInvocation(
            ToolInvocationId id,
            @Nullable MessageId messageId,
            String toolName,
            Map<String, Object> arguments,
            @Nullable Map<String, Object> result,
            ToolCallOutcome outcome,
            long durationMs) {
        this.id = id.value();
        this.messageId = messageId == null ? null : messageId.value();
        this.toolName = toolName;
        this.arguments = arguments;
        this.result = result;
        this.outcome = outcome;
        this.durationMs = durationMs;
        this.createdAt = Instant.now();
    }

    public ToolInvocationId id() {
        return ToolInvocationId.of(id);
    }

    public @Nullable MessageId messageId() {
        return messageId == null ? null : MessageId.of(messageId);
    }

    public String toolName() {
        return toolName;
    }

    public Map<String, Object> arguments() {
        return arguments;
    }

    public @Nullable Map<String, Object> result() {
        return result;
    }

    public ToolCallOutcome outcome() {
        return outcome;
    }

    public long durationMs() {
        return durationMs;
    }

    public Instant createdAt() {
        return createdAt;
    }
}
