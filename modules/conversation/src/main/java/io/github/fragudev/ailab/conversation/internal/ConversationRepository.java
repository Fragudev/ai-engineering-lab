package io.github.fragudev.ailab.conversation.internal;

import io.github.fragudev.ailab.conversation.Conversation;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ConversationRepository extends JpaRepository<Conversation, UUID> {}
