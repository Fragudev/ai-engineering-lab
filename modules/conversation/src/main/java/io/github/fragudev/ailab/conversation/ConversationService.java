package io.github.fragudev.ailab.conversation;

import io.github.fragudev.ailab.aiprovider.ChatChunk;
import io.github.fragudev.ailab.aiprovider.ChatMessage;
import io.github.fragudev.ailab.aiprovider.ChatProvider;
import io.github.fragudev.ailab.aiprovider.ChatRequest;
import io.github.fragudev.ailab.aiprovider.ChatResponse;
import io.github.fragudev.ailab.conversation.internal.ConversationRepository;
import io.github.fragudev.ailab.conversation.internal.MessageRepository;
import io.github.fragudev.ailab.shared.ConversationId;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

/**
 * Drives one conversation turn: append the user message, call {@link ChatProvider#stream}, and
 * persist the assistant message with its usage/latency/cost once the terminal chunk arrives.
 */
@Service
public class ConversationService {

    private final ConversationRepository conversationRepository;
    private final MessageRepository messageRepository;
    private final ChatProvider chatProvider;

    public ConversationService(
            ConversationRepository conversationRepository, MessageRepository messageRepository, ChatProvider chatProvider) {
        this.conversationRepository = conversationRepository;
        this.messageRepository = messageRepository;
        this.chatProvider = chatProvider;
    }

    public Conversation createConversation() {
        return conversationRepository.save(new Conversation(ConversationId.generate()));
    }

    public Conversation getConversation(ConversationId id) {
        return conversationRepository
                .findById(id.value())
                .orElseThrow(() -> new NoSuchElementException("No conversation with id " + id));
    }

    public List<Message> getMessages(ConversationId conversationId) {
        return messageRepository.findByConversationIdOrderByCreatedAtAsc(conversationId.value());
    }

    /**
     * Appends the user message, streams the assistant's reply, and persists it once complete.
     * Errors from the provider (timeout, unavailable) propagate as-is on the returned {@link Flux}
     * for the caller to translate at the API edge.
     */
    public Flux<ChatChunk> sendMessage(ConversationId conversationId, String userContent) {
        Conversation conversation = getConversation(conversationId);
        messageRepository.save(Message.userMessage(conversation.id(), userContent));

        List<ChatMessage> history = messageRepository.findByConversationIdOrderByCreatedAtAsc(conversation.id().value())
                .stream()
                .map(message -> new ChatMessage(message.role(), message.content()))
                .toList();

        return chatProvider
                .stream(new ChatRequest(history))
                .doOnNext(chunk -> {
                    if (chunk.last()) {
                        persistAssistantReply(conversation.id(), chunk.aggregate());
                    }
                });
    }

    private void persistAssistantReply(ConversationId conversationId, ChatResponse aggregate) {
        Optional.ofNullable(aggregate)
                .ifPresent(response -> messageRepository.save(Message.assistantMessage(
                        conversationId,
                        response.content(),
                        response.model(),
                        response.usage().promptTokens(),
                        response.usage().completionTokens(),
                        response.latency().toMillis(),
                        response.estimatedCostUsd())));
    }
}
