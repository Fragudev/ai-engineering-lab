package io.github.fragudev.ailab.conversation;

import io.github.fragudev.ailab.aiprovider.ChatMessage;
import io.github.fragudev.ailab.aiprovider.ChatProvider;
import io.github.fragudev.ailab.aiprovider.ChatResponse;
import io.github.fragudev.ailab.conversation.internal.CitationRepository;
import io.github.fragudev.ailab.conversation.internal.ConversationRepository;
import io.github.fragudev.ailab.conversation.internal.MessageRepository;
import io.github.fragudev.ailab.shared.CitationId;
import io.github.fragudev.ailab.shared.ConversationId;
import io.github.fragudev.ailab.shared.MessageId;
import io.github.fragudev.ailab.tools.ToolCallOrigin;
import io.github.fragudev.ailab.tools.ToolCallingChatService;
import io.github.fragudev.ailab.tools.ToolChatChunk;
import io.github.fragudev.ailab.tools.ToolInvoker;
import io.github.fragudev.ailab.tools.ToolRegistry;
import java.math.BigDecimal;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

/**
 * Drives one conversation turn: append the user message, call {@link ChatProvider#stream}, and
 * persist the assistant message with its usage/latency/cost once the terminal chunk arrives. This is
 * the plain-chat path, unchanged since Phase 1. The RAG-augmented path ({@code app}, via {@code rag})
 * uses {@link #appendUserMessage}, {@link #getHistoryAsChatMessages} and
 * {@link #recordAssistantAnswer} instead, since generation there is {@code rag}'s job, not this
 * module's — see docs/adr/0008-rag-pipeline-architecture.md.
 */
@Service
public class ConversationService {

    private final ConversationRepository conversationRepository;
    private final MessageRepository messageRepository;
    private final CitationRepository citationRepository;
    private final ChatProvider chatProvider;
    private final ToolCallingChatService toolCallingChatService;
    private final ToolRegistry toolRegistry;
    private final ToolInvoker toolInvoker;

    public ConversationService(
            ConversationRepository conversationRepository,
            MessageRepository messageRepository,
            CitationRepository citationRepository,
            ChatProvider chatProvider,
            ToolCallingChatService toolCallingChatService,
            ToolRegistry toolRegistry,
            ToolInvoker toolInvoker) {
        this.conversationRepository = conversationRepository;
        this.messageRepository = messageRepository;
        this.citationRepository = citationRepository;
        this.chatProvider = chatProvider;
        this.toolCallingChatService = toolCallingChatService;
        this.toolRegistry = toolRegistry;
        this.toolInvoker = toolInvoker;
    }

    public Conversation createConversation() {
        return createConversation(null);
    }

    public Conversation createConversation(@Nullable String ragProfile) {
        return conversationRepository.save(new Conversation(ConversationId.generate(), ragProfile));
    }

    public Conversation getConversation(ConversationId id) {
        return conversationRepository
                .findById(id.value())
                .orElseThrow(() -> new NoSuchElementException("No conversation with id " + id));
    }

    public List<Message> getMessages(ConversationId conversationId) {
        return messageRepository.findByConversationIdOrderByCreatedAtAsc(conversationId.value());
    }

    public List<Citation> getCitations(MessageId messageId) {
        return citationRepository.findByMessageIdOrderByOrdinalAsc(messageId.value());
    }

    /** Persists the user's turn without generating a reply — the RAG path's caller ({@code app})
     * fetches history first via {@link #getHistoryAsChatMessages}, then calls this, so the history it
     * passed to {@code rag} never includes the message being appended here. */
    public Message appendUserMessage(ConversationId conversationId, String content) {
        Conversation conversation = getConversation(conversationId);
        return messageRepository.save(Message.userMessage(conversation.id(), content));
    }

    public List<ChatMessage> getHistoryAsChatMessages(ConversationId conversationId) {
        return messageRepository.findByConversationIdOrderByCreatedAtAsc(conversationId.value()).stream()
                .map(message -> new ChatMessage(message.role(), message.content()))
                .toList();
    }

    /** Persists a RAG-generated assistant reply and its citations in one call. */
    public Message recordAssistantAnswer(
            ConversationId conversationId,
            String content,
            String model,
            int promptTokens,
            int completionTokens,
            long latencyMs,
            BigDecimal estimatedCostUsd,
            List<CitationInput> citations) {
        Message message = messageRepository.save(Message.assistantMessage(
                conversationId, content, model, promptTokens, completionTokens, latencyMs, estimatedCostUsd));
        int ordinal = 1;
        for (CitationInput citation : citations) {
            citationRepository.save(new Citation(
                    CitationId.generate(),
                    message.id(),
                    citation.chunkId(),
                    citation.documentId(),
                    citation.score(),
                    citation.quotedSpan(),
                    ordinal++));
        }
        return message;
    }

    /**
     * Appends the user message, streams the assistant's reply — through
     * {@link ToolCallingChatService} rather than {@link ChatProvider} directly, so plain chat gets
     * tool calling too (Phase 5, ADR-0009) — and persists it once complete. Any tool calls made
     * during the turn are persisted via {@link ToolInvoker#recordForMessage} right after, once the
     * owning assistant message exists (mirrors how RAG citations are linked in {@code app}). Errors
     * from the provider (timeout, unavailable) propagate as-is on the returned {@link Flux} for the
     * caller to translate at the API edge.
     */
    public Flux<ToolChatChunk> sendMessage(ConversationId conversationId, String userContent) {
        Conversation conversation = getConversation(conversationId);
        messageRepository.save(Message.userMessage(conversation.id(), userContent));

        List<ChatMessage> history =
                messageRepository
                        .findByConversationIdOrderByCreatedAtAsc(
                                conversation.id().value())
                        .stream()
                        .map(message -> new ChatMessage(message.role(), message.content()))
                        .toList();

        return toolCallingChatService.stream(
                        chatProvider, history, toolRegistry.definitions(), ToolCallOrigin.PLAIN_CHAT, conversationId)
                .doOnNext(chunk -> {
                    if (chunk.last()) {
                        Message assistantMessage = persistAssistantReply(conversation.id(), chunk.aggregate());
                        if (assistantMessage != null) {
                            chunk.toolInvocations()
                                    .forEach(result -> toolInvoker.recordForMessage(assistantMessage.id(), result));
                        }
                    }
                });
    }

    private @Nullable Message persistAssistantReply(ConversationId conversationId, @Nullable ChatResponse aggregate) {
        return Optional.ofNullable(aggregate)
                .map(response -> messageRepository.save(Message.assistantMessage(
                        conversationId,
                        response.content(),
                        response.model(),
                        response.usage().promptTokens(),
                        response.usage().completionTokens(),
                        response.latency().toMillis(),
                        response.estimatedCostUsd())))
                .orElse(null);
    }
}
