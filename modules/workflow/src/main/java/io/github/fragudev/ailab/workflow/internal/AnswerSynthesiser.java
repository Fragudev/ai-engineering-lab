package io.github.fragudev.ailab.workflow.internal;

import io.github.fragudev.ailab.aiprovider.ChatMessage;
import io.github.fragudev.ailab.aiprovider.ChatProvider;
import io.github.fragudev.ailab.aiprovider.ChatRequest;
import io.github.fragudev.ailab.aiprovider.ChatResponse;
import java.math.BigDecimal;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * The {@code synthesise} stage's LLM call. Unlike {@link SubQueryPlanner}/{@link SourceExtractor},
 * this one has no graceful degrade — there's no sensible fallback for "no answer" — so a provider
 * failure is left to propagate to {@link StageRunner}'s own retry/compensation handling rather than
 * being caught here.
 */
@Component
class AnswerSynthesiser {

    private static final String INSTRUCTION =
            "Answer the user's question using ONLY the numbered sources below. Cite each claim with the "
                    + "matching [n] marker for the source it came from. If the sources don't fully answer the "
                    + "question, say so plainly rather than guessing.";

    private final ChatProvider chatProvider;

    AnswerSynthesiser(ChatProvider chatProvider) {
        this.chatProvider = chatProvider;
    }

    SynthesisResult synthesise(String query, List<ExtractedSource> sources, @Nullable String corrective) {
        ChatResponse response = chatProvider.complete(new ChatRequest(
                List.of(ChatMessage.system(INSTRUCTION), ChatMessage.user(buildPrompt(query, sources, corrective)))));
        return new SynthesisResult(response.content().trim(), response.estimatedCostUsd());
    }

    private static String buildPrompt(String query, List<ExtractedSource> sources, @Nullable String corrective) {
        StringBuilder prompt = new StringBuilder("Question: ").append(query).append("\n\nSources:\n");
        for (ExtractedSource source : sources) {
            prompt.append('[')
                    .append(source.marker())
                    .append("] ")
                    .append(source.facts())
                    .append('\n');
        }
        if (corrective != null) {
            prompt.append('\n').append(corrective);
        }
        return prompt.toString();
    }

    record SynthesisResult(String answer, BigDecimal costUsd) {}
}
