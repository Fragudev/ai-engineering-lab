package io.github.fragudev.ailab.rag;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.fragudev.ailab.knowledge.Chunk;
import io.github.fragudev.ailab.knowledge.SearchResult;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Regression coverage for the abstention gate's threshold, recalibrated in post-roadmap review
 * issue #29 (docs/adr/0013-rag-abstention-threshold.md). Distance values below are real
 * measurements against the live bge-m3 embedding model, not invented: the answerable-question
 * distribution (eval/dataset/core.yaml's 28 cases) ran 0.2989–0.4682, and four genuinely off-topic
 * control queries (capital of France, a cake recipe, chess rules, general relativity — none of it
 * in this project's 2-document corpus) ran 0.5992–0.6866. {@code maxVectorDistance} sits at 0.55,
 * in the gap between the two clusters.
 */
class RagPipelineAbstentionTest {

    private static final RagProfile PROFILE = RagProfiles.DENSE_ONLY;

    @ParameterizedTest
    @ValueSource(doubles = {0.2989, 0.3597, 0.4682, 0.5499})
    void doesNotAbstainWithinTheAnswerableDistribution(double distance) {
        assertThat(RagPipeline.shouldAbstain(List.of(resultAt(distance)), PROFILE))
                .isFalse();
    }

    @ParameterizedTest
    @ValueSource(doubles = {0.5501, 0.5992, 0.6433, 0.6866})
    void abstainsOnGenuinelyOffTopicDistances(double distance) {
        assertThat(RagPipeline.shouldAbstain(List.of(resultAt(distance)), PROFILE))
                .isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"dense-only", "hybrid", "hybrid-rerank", "hybrid-rerank-llm"})
    void thresholdIsIdenticalAcrossEveryProfile(String profileName) {
        RagProfile profile = RagProfiles.byName(profileName).orElseThrow();
        assertThat(RagPipeline.shouldAbstain(List.of(resultAt(0.60)), profile)).isTrue();
        assertThat(RagPipeline.shouldAbstain(List.of(resultAt(0.40)), profile)).isFalse();
    }

    @org.junit.jupiter.api.Test
    void abstainsWhenNoCandidatesAreReturned() {
        assertThat(RagPipeline.shouldAbstain(List.of(), PROFILE)).isTrue();
    }

    @org.junit.jupiter.api.Test
    void usesTheClosestCandidateWhenSeveralAreReturned() {
        List<SearchResult> results = List.of(resultAt(0.68), resultAt(0.30), resultAt(0.50));
        assertThat(RagPipeline.shouldAbstain(results, PROFILE)).isFalse();
    }

    private static SearchResult resultAt(double vectorDistance) {
        Chunk chunk =
                new Chunk(UUID.randomUUID(), UUID.randomUUID(), 0, "irrelevant for this gate", 0, null, new float[0]);
        return new SearchResult(chunk, vectorDistance, null, 0.0, null, 1);
    }
}
