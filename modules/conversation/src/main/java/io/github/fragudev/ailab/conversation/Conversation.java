package io.github.fragudev.ailab.conversation;

import io.github.fragudev.ailab.shared.ConversationId;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

@Entity
@Table(name = "conversation")
public class Conversation {

    @Id
    private UUID id;

    @Nullable
    private String title;

    @Nullable
    @Column(name = "rag_profile")
    private String ragProfile;

    @Column(name = "created_at")
    private Instant createdAt;

    @Column(name = "updated_at")
    private Instant updatedAt;

    protected Conversation() {
        // JPA
    }

    public Conversation(ConversationId id) {
        this(id, null);
    }

    /** {@code ragProfile == null} means plain chat, no retrieval — unchanged Phase 1 behavior; a
     * non-null value must name one of {@code RagProfiles.all()} (validated at the API edge, since
     * this module doesn't depend on {@code rag}). */
    public Conversation(ConversationId id, @Nullable String ragProfile) {
        this.id = id.value();
        this.ragProfile = ragProfile;
        Instant now = Instant.now();
        this.createdAt = now;
        this.updatedAt = now;
    }

    public ConversationId id() {
        return ConversationId.of(id);
    }

    public @Nullable String title() {
        return title;
    }

    public @Nullable String ragProfile() {
        return ragProfile;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public Instant updatedAt() {
        return updatedAt;
    }
}
