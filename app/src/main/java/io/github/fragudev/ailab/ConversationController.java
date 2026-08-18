package io.github.fragudev.ailab;

import io.github.fragudev.ailab.conversation.ConversationService;
import io.github.fragudev.ailab.shared.ConversationId;
import jakarta.validation.Valid;
import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
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

    ConversationController(ConversationService conversationService) {
        this.conversationService = conversationService;
    }

    @PostMapping
    ResponseEntity<ConversationResponse> create() {
        var conversation = conversationService.createConversation();
        return ResponseEntity.status(HttpStatus.CREATED).body(ConversationResponse.from(conversation));
    }

    @GetMapping("/{id}")
    ConversationResponse get(@PathVariable UUID id) {
        return ConversationResponse.from(conversationService.getConversation(ConversationId.of(id)));
    }

    @GetMapping("/{id}/messages")
    List<MessageResponse> messages(@PathVariable UUID id) {
        return conversationService.getMessages(ConversationId.of(id)).stream()
                .map(MessageResponse::from)
                .toList();
    }

    @PostMapping(value = "/{id}/messages", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    SseEmitter sendMessage(@PathVariable UUID id, @Valid @RequestBody SendMessageRequest request) {
        // Looked up eagerly (throws synchronously -> a real 404) before the SSE stream commits;
        // see openapi.yaml for why a failure after this point can only surface in-band.
        ConversationId conversationId = ConversationId.of(id);
        conversationService.getConversation(conversationId);

        // Captured on the request thread: once streaming starts, the provider call may continue
        // on a different thread, and MDC does not reliably follow it.
        String traceId = MDC.get("traceId");

        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT.toMillis());
        conversationService
                .sendMessage(conversationId, request.content())
                .subscribe(
                        chunk -> onChunk(emitter, chunk, traceId),
                        error -> onError(emitter, error),
                        () -> onComplete(emitter));
        return emitter;
    }

    private static void onChunk(
            SseEmitter emitter, io.github.fragudev.ailab.aiprovider.ChatChunk chunk, String traceId) {
        try {
            if (chunk.last()) {
                emitter.send(SseEmitter.event()
                        .name("usage")
                        .data(UsageSummary.from(chunk.aggregate(), traceId), MediaType.APPLICATION_JSON));
            } else {
                emitter.send(SseEmitter.event().name("token").data(chunk.deltaContent()));
            }
        } catch (IOException e) {
            emitter.completeWithError(e);
        }
    }

    private static void onError(SseEmitter emitter, Throwable error) {
        try {
            emitter.send(SseEmitter.event().name("error").data(ProblemDetails.of(error), MediaType.APPLICATION_JSON));
        } catch (IOException ignored) {
            // The client is already gone; nothing left to notify.
        }
        emitter.completeWithError(error);
    }

    private static void onComplete(SseEmitter emitter) {
        try {
            emitter.send(SseEmitter.event().name("done").data(""));
        } catch (IOException ignored) {
            // The client is already gone; nothing left to notify.
        }
        emitter.complete();
    }
}
