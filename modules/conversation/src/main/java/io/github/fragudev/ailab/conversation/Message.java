package io.github.fragudev.ailab.conversation;

import io.github.fragudev.ailab.aiprovider.ChatRole;
import io.github.fragudev.ailab.shared.ConversationId;
import io.github.fragudev.ailab.shared.MessageId;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

@Entity
@Table(name = "message")
public class Message {

    @Id
    private UUID id;

    @Column(name = "conversation_id")
    private UUID conversationId;

    @Enumerated(EnumType.STRING)
    private ChatRole role;

    @Column(columnDefinition = "text")
    private String content;

    @Nullable
    private String model;

    @Nullable
    @Column(name = "prompt_tokens")
    private Integer promptTokens;

    @Nullable
    @Column(name = "completion_tokens")
    private Integer completionTokens;

    @Nullable
    @Column(name = "latency_ms")
    private Long latencyMs;

    @Nullable
    @Column(name = "estimated_cost_usd")
    private BigDecimal estimatedCostUsd;

    @Column(name = "created_at")
    private Instant createdAt;

    protected Message() {
        // JPA
    }

    private Message(
            MessageId id,
            ConversationId conversationId,
            ChatRole role,
            String content,
            @Nullable String model,
            @Nullable Integer promptTokens,
            @Nullable Integer completionTokens,
            @Nullable Long latencyMs,
            @Nullable BigDecimal estimatedCostUsd) {
        this.id = id.value();
        this.conversationId = conversationId.value();
        this.role = role;
        this.content = content;
        this.model = model;
        this.promptTokens = promptTokens;
        this.completionTokens = completionTokens;
        this.latencyMs = latencyMs;
        this.estimatedCostUsd = estimatedCostUsd;
        this.createdAt = Instant.now();
    }

    public static Message userMessage(ConversationId conversationId, String content) {
        return new Message(MessageId.generate(), conversationId, ChatRole.USER, content, null, null, null, null, null);
    }

    public static Message assistantMessage(
            ConversationId conversationId,
            String content,
            String model,
            int promptTokens,
            int completionTokens,
            long latencyMs,
            BigDecimal estimatedCostUsd) {
        return new Message(
                MessageId.generate(),
                conversationId,
                ChatRole.ASSISTANT,
                content,
                model,
                promptTokens,
                completionTokens,
                latencyMs,
                estimatedCostUsd);
    }

    public MessageId id() {
        return MessageId.of(id);
    }

    public ConversationId conversationId() {
        return ConversationId.of(conversationId);
    }

    public ChatRole role() {
        return role;
    }

    public String content() {
        return content;
    }

    public @Nullable String model() {
        return model;
    }

    public @Nullable Integer promptTokens() {
        return promptTokens;
    }

    public @Nullable Integer completionTokens() {
        return completionTokens;
    }

    public @Nullable Long latencyMs() {
        return latencyMs;
    }

    public @Nullable BigDecimal estimatedCostUsd() {
        return estimatedCostUsd;
    }

    public Instant createdAt() {
        return createdAt;
    }
}
