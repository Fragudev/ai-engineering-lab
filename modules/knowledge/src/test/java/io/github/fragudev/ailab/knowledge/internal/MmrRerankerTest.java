package io.github.fragudev.ailab.knowledge.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.offset;

import io.github.fragudev.ailab.knowledge.Chunk;
import io.github.fragudev.ailab.knowledge.SearchResult;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Pure-function coverage for MMR selection — relevance vs. redundancy trade-off, embeddings only,
 * no model call (post-roadmap review issue #30) — none of this needed Postgres or Kafka. */
class MmrRerankerTest {

    private final MmrReranker reranker = new MmrReranker();

    @Test
    void picksTheMostRelevantCandidateFirst() {
        float[] query = {1f, 0f};
        SearchResult mostRelevant = resultOf(new float[] {4f, 3f}); // cos = 0.8
        SearchResult lessRelevant = resultOf(new float[] {3f, -4f}); // cos = 0.6

        List<SearchResult> selected = reranker.rerank(query, "ignored", List.of(lessRelevant, mostRelevant), 1);

        assertThat(selected).hasSize(1);
        assertThat(selected.get(0).chunk()).isEqualTo(mostRelevant.chunk());
    }

    @Test
    void prefersADiverseLowerRelevanceCandidateOverANearDuplicateOfAnAlreadySelectedOne() {
        float[] query = {1f, 0f};
        SearchResult a = resultOf(new float[] {4f, 3f}); // cos(q,A) = 0.8 -- picked first
        SearchResult nearDuplicateOfA = resultOf(new float[] {4f, 3.5f}); // cos(q,B) ~= 0.753, cos(A,B) ~= 0.997
        SearchResult diverseCandidate = resultOf(new float[] {3f, -4f}); // cos(q,C) = 0.6, cos(A,C) = 0

        List<SearchResult> selected =
                reranker.rerank(query, "ignored", List.of(a, nearDuplicateOfA, diverseCandidate), 2);

        assertThat(selected).extracting(SearchResult::chunk).containsExactly(a.chunk(), diverseCandidate.chunk());
    }

    @Test
    void stampsSequentialOneBasedFinalRankOnTheSelection() {
        float[] query = {1f, 0f};
        SearchResult a = resultOf(new float[] {4f, 3f});
        SearchResult b = resultOf(new float[] {3f, -4f});

        List<SearchResult> selected = reranker.rerank(query, "ignored", List.of(a, b), 2);

        assertThat(selected.get(0).finalRank()).isEqualTo(1);
        assertThat(selected.get(1).finalRank()).isEqualTo(2);
    }

    @Test
    void neverReturnsMoreThanTopKEvenWithMoreCandidates() {
        float[] query = {1f, 0f};
        List<SearchResult> candidates = List.of(
                resultOf(new float[] {1f, 0f}), resultOf(new float[] {0f, 1f}), resultOf(new float[] {-1f, 0f}));

        List<SearchResult> selected = reranker.rerank(query, "ignored", candidates, 2);

        assertThat(selected).hasSize(2);
    }

    @Test
    void returnsAllCandidatesWhenFewerThanTopK() {
        float[] query = {1f, 0f};
        List<SearchResult> candidates = List.of(resultOf(new float[] {1f, 0f}));

        List<SearchResult> selected = reranker.rerank(query, "ignored", candidates, 5);

        assertThat(selected).hasSize(1);
    }

    @Test
    void cosineSimilarityOfIdenticalVectorsIsOne() {
        assertThat(MmrReranker.cosineSimilarity(new float[] {3f, 4f}, new float[] {3f, 4f}))
                .isCloseTo(1.0, offset(1e-9));
    }

    @Test
    void cosineSimilarityOfOrthogonalVectorsIsZero() {
        assertThat(MmrReranker.cosineSimilarity(new float[] {1f, 0f}, new float[] {0f, 1f}))
                .isCloseTo(0.0, offset(1e-9));
    }

    @Test
    void cosineSimilarityIsScaleInvariant() {
        double small = MmrReranker.cosineSimilarity(new float[] {1f, 1f}, new float[] {2f, 0f});
        double large = MmrReranker.cosineSimilarity(new float[] {100f, 100f}, new float[] {2f, 0f});
        assertThat(small).isCloseTo(large, offset(1e-9));
    }

    @Test
    void cosineSimilarityOfAZeroVectorIsZeroRatherThanNaN() {
        assertThat(MmrReranker.cosineSimilarity(new float[] {0f, 0f}, new float[] {1f, 1f}))
                .isZero();
    }

    private static SearchResult resultOf(float[] embedding) {
        Chunk chunk = new Chunk(UUID.randomUUID(), UUID.randomUUID(), 0, "content", 0, null, embedding);
        return new SearchResult(chunk, null, null, 0.0, null, 1);
    }
}
