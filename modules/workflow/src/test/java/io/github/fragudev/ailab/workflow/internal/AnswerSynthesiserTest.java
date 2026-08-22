package io.github.fragudev.ailab.workflow.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AnswerSynthesiserTest {

    @Test
    void synthesisesAnAnswerCitingSources() {
        AnswerSynthesiser synthesiser = new AnswerSynthesiser(new FakeChatProvider("Water boils at 100C [1]."));
        List<ExtractedSource> sources =
                List.of(new ExtractedSource(UUID.randomUUID(), UUID.randomUUID(), 1, "Water boils at 100C."));

        AnswerSynthesiser.SynthesisResult result = synthesiser.synthesise("query", sources, null);

        assertThat(result.answer()).isEqualTo("Water boils at 100C [1].");
    }

    @Test
    void providerFailurePropagatesRatherThanFallingBack() {
        // Unlike SubQueryPlanner/SourceExtractor, there's no sensible fallback for "no answer" — a
        // failed synthesis is left to StageRunner's own retry/compensation handling.
        AnswerSynthesiser synthesiser =
                new AnswerSynthesiser(FakeChatProvider.failingWith(new RuntimeException("boom")));

        assertThatThrownBy(() -> synthesiser.synthesise("query", List.of(), null))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("boom");
    }
}
