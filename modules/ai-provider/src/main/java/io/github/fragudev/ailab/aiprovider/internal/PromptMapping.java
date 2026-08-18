package io.github.fragudev.ailab.aiprovider.internal;

import io.github.fragudev.ailab.aiprovider.ChatMessage;
import io.github.fragudev.ailab.aiprovider.ChatRequest;
import java.util.List;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;

/** Maps our project-owned {@link ChatRequest} onto Spring AI's {@link Prompt}, and nothing else. */
final class PromptMapping {

    private PromptMapping() {}

    static Prompt toPrompt(ChatRequest request) {
        List<Message> messages =
                request.messages().stream().map(PromptMapping::toSpringMessage).toList();
        return new Prompt(messages);
    }

    private static Message toSpringMessage(ChatMessage message) {
        return switch (message.role()) {
            case USER -> new UserMessage(message.content());
            case ASSISTANT -> new AssistantMessage(message.content());
            case SYSTEM, TOOL -> new SystemMessage(message.content());
        };
    }
}
