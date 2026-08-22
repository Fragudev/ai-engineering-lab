package io.github.fragudev.ailab;

import io.github.fragudev.ailab.aiprovider.ChatMessage;
import io.github.fragudev.ailab.conversation.CitationInput;
import io.github.fragudev.ailab.conversation.Conversation;
import io.github.fragudev.ailab.conversation.ConversationService;
import io.github.fragudev.ailab.conversation.Message;
import io.github.fragudev.ailab.rag.RagAnswer;
import io.github.fragudev.ailab.rag.RagAnswerChunk;
import io.github.fragudev.ailab.rag.RagCitationResult;
import io.github.fragudev.ailab.rag.RagPipeline;
import io.github.fragudev.ailab.rag.RagProfile;
import io.github.fragudev.ailab.shared.ConversationId;
import io.github.fragudev.ailab.shared.DocumentId;
import io.github.fragudev.ailab.tools.ToolChatChunk;
import io.github.fragudev.ailab.tools.ToolInvoker;
import jakarta.validation.Valid;
import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping("/api/v1/conversations")
class ConversationController {

    private static final Duration SSE_TIMEOUT = Duration.ofMinutes(5);

    private final ConversationService conversationService;
    private final RagPipeline ragPipeline;
    private final ToolInvoker toolInvoker;

    ConversationController(ConversationService conversationService, RagPipeline ragPipeline, ToolInvoker toolInvoker) {
        this.conversationService = conversationService;
        this.ragPipeline = ragPipeline;
        this.toolInvoker = toolInvoker;
    }

    @PostMapping
    ResponseEntity<ConversationResponse> create(
            @RequestBody(required = false) @Nullable CreateConversationRequest request) {
        String ragProfile = request == null ? null : request.ragProfile();
        if (ragProfile != null) {
            RetrievalController.resolveProfile(ragProfile); // throws 400 if unknown, before persisting
        }
        var conversation = conversationService.createConversation(ragProfile);
        return ResponseEntity.status(HttpStatus.CREATED).body(ConversationResponse.from(conversation));
    }

    @GetMapping("/{id}")
    ConversationResponse get(@PathVariable UUID id) {
        return ConversationResponse.from(conversationService.getConversation(ConversationId.of(id)));
    }

    @GetMapping("/{id}/messages")
    List<MessageResponse> messages(@PathVariable UUID id) {
        return conversationService.getMessages(ConversationId.of(id)).stream()
                .map(message -> MessageResponse.from(message, conversationService.getCitations(message.id())))
                .toList();
    }

    @PostMapping(value = "/{id}/messages", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    SseEmitter sendMessage(@PathVariable UUID id, @Valid @RequestBody SendMessageRequest request) {
        // Looked up eagerly (throws synchronously -> a real 404) before the SSE stream commits;
        // see openapi.yaml for why a failure after this point can only surface in-band.
        ConversationId conversationId = ConversationId.of(id);
        Conversation conversation = conversationService.getConversation(conversationId);

        String effectiveProfileName = request.ragProfile() != null ? request.ragProfile() : conversation.ragProfile();

        // Captured on the request thread: once streaming starts, the provider call may continue
        // on a different thread, and MDC does not reliably follow it.
        String traceId = MDC.get("traceId");
        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT.toMillis());

        if (effectiveProfileName == null) {
            conversationService
                    .sendMessage(conversationId, request.content())
                    .subscribe(
                            chunk -> onChunk(emitter, chunk, traceId),
                            error -> onError(emitter, error),
                            () -> onComplete(emitter));
            return emitter;
        }

        RagProfile profile = RetrievalController.resolveProfile(effectiveProfileName);
        List<ChatMessage> history = conversationService.getHistoryAsChatMessages(conversationId);
        conversationService.appendUserMessage(conversationId, request.content());

        ragPipeline
                .answer(history, request.content(), profile)
                .subscribe(
                        chunk -> onRagChunk(emitter, conversationId, chunk, traceId),
                        error -> onError(emitter, error),
                        () -> onComplete(emitter));
        return emitter;
    }

    // Persistence for the plain-chat path (including tool invocations) already happens inside
    // ConversationService.sendMessage itself, since that module owns ChatProvider directly — this
    // just renders SSE events. The RAG path is the opposite (rag doesn't depend on conversation),
    // so persistRagAnswer below does it here, same asymmetry this class already had pre-Phase 5.
    private static void onChunk(SseEmitter emitter, ToolChatChunk chunk, @Nullable String traceId) {
        try {
            if (chunk.last()) {
                synchronized (emitter) {
                    emitter.send(SseEmitter.event()
                            .name("usage")
                            .data(UsageSummary.from(chunk.aggregate(), traceId), MediaType.APPLICATION_JSON));
                }
            } else if (chunk.toolCall() != null) {
                synchronized (emitter) {
                    emitter.send(SseEmitter.event()
                            .name("tool_call")
                            .data(ToolCallEvent.from(chunk.toolCall()), MediaType.APPLICATION_JSON));
                }
            } else if (chunk.toolResult() != null) {
                synchronized (emitter) {
                    emitter.send(SseEmitter.event()
                            .name("tool_result")
                            .data(ToolResultEvent.from(chunk.toolResult()), MediaType.APPLICATION_JSON));
                }
            } else if (chunk.pendingConfirmation() != null) {
                synchronized (emitter) {
                    emitter.send(SseEmitter.event()
                            .name("tool_call_pending")
                            .data(
                                    ToolPendingConfirmationEvent.from(chunk.pendingConfirmation()),
                                    MediaType.APPLICATION_JSON));
                }
            } else if (!chunk.deltaContent().isEmpty()) {
                synchronized (emitter) {
                    emitter.send(SseEmitter.event().name("token").data(chunk.deltaContent()));
                }
            }
        } catch (IOException e) {
            emitter.completeWithError(e);
        }
    }

    private void onRagChunk(
            SseEmitter emitter, ConversationId conversationId, RagAnswerChunk chunk, @Nullable String traceId) {
        try {
            if (chunk.last()) {
                RagAnswer answer = chunk.aggregate();
                persistRagAnswer(conversationId, answer);
                synchronized (emitter) {
                    emitter.send(SseEmitter.event()
                            .name("usage")
                            .data(UsageSummary.from(answer, traceId), MediaType.APPLICATION_JSON));
                }
            } else if (chunk.citation() != null) {
                synchronized (emitter) {
                    emitter.send(SseEmitter.event()
                            .name("citation")
                            .data(CitationEvent.from(chunk.citation()), MediaType.APPLICATION_JSON));
                }
            } else if (chunk.toolCall() != null) {
                synchronized (emitter) {
                    emitter.send(SseEmitter.event()
                            .name("tool_call")
                            .data(ToolCallEvent.from(chunk.toolCall()), MediaType.APPLICATION_JSON));
                }
            } else if (chunk.toolResult() != null) {
                synchronized (emitter) {
                    emitter.send(SseEmitter.event()
                            .name("tool_result")
                            .data(ToolResultEvent.from(chunk.toolResult()), MediaType.APPLICATION_JSON));
                }
            } else if (chunk.pendingConfirmation() != null) {
                synchronized (emitter) {
                    emitter.send(SseEmitter.event()
                            .name("tool_call_pending")
                            .data(
                                    ToolPendingConfirmationEvent.from(chunk.pendingConfirmation()),
                                    MediaType.APPLICATION_JSON));
                }
            } else if (!chunk.deltaContent().isEmpty()) {
                synchronized (emitter) {
                    emitter.send(SseEmitter.event().name("token").data(chunk.deltaContent()));
                }
            }
        } catch (IOException e) {
            emitter.completeWithError(e);
        }
    }

    private void persistRagAnswer(ConversationId conversationId, RagAnswer answer) {
        List<CitationInput> citations = answer.citations().stream()
                .map(ConversationController::toCitationInput)
                .toList();
        Message message = conversationService.recordAssistantAnswer(
                conversationId,
                answer.content(),
                answer.model(),
                answer.usage().promptTokens(),
                answer.usage().completionTokens(),
                answer.latency().toMillis(),
                answer.estimatedCostUsd(),
                citations);
        answer.toolInvocations().forEach(result -> toolInvoker.recordForMessage(message.id(), result));
    }

    private static CitationInput toCitationInput(RagCitationResult citation) {
        return new CitationInput(
                citation.chunkId(), DocumentId.of(citation.documentId()), citation.score(), citation.quotedSpan());
    }

    private static void onError(SseEmitter emitter, Throwable error) {
        try {
            synchronized (emitter) {
                emitter.send(
                        SseEmitter.event().name("error").data(ProblemDetails.of(error), MediaType.APPLICATION_JSON));
            }
        } catch (IOException ignored) {
            // The client is already gone; nothing left to notify.
        }
        emitter.completeWithError(error);
    }

    private static void onComplete(SseEmitter emitter) {
        try {
            synchronized (emitter) {
                emitter.send(SseEmitter.event().name("done").data(""));
            }
        } catch (IOException ignored) {
            // The client is already gone; nothing left to notify.
        }
        emitter.complete();
    }
}
