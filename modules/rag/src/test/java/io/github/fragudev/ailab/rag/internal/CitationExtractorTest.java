package io.github.fragudev.ailab.rag.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Pure-function coverage for marker parsing, streaming-safe stripping, and resolution
 * (post-roadmap review issue #30) — none of this needed Postgres or Kafka. */
class CitationExtractorTest {

    @Test
    void stripDeltaRemovesACompleteMarkerWithinOneDelta() {
        CitationExtractor extractor = new CitationExtractor();

        String out = extractor.stripDelta("pgvector uses cosine distance [1].");

        assertThat(out).isEqualTo("pgvector uses cosine distance .");
    }

    @Test
    void stripDeltaBuffersAMarkerSplitAcrossAChunkBoundary() {
        CitationExtractor extractor = new CitationExtractor();

        String firstOut = extractor.stripDelta("some fact ");
        String secondOut = extractor.stripDelta("[");
        String thirdOut = extractor.stripDelta("1] more text");

        assertThat(firstOut).isEqualTo("some fact ");
        assertThat(secondOut).isEmpty();
        assertThat(thirdOut).isEqualTo(" more text");
    }

    @Test
    void stripDeltaDoesNotBufferABracketThatCannotBecomeAMarker() {
        CitationExtractor extractor = new CitationExtractor();

        String out = extractor.stripDelta("an array literal [a, b]");

        assertThat(out).isEqualTo("an array literal [a, b]");
    }

    @Test
    void flushRemainingReturnsAnUnfinishedMarkerAtStreamEndAsPlainText() {
        CitationExtractor extractor = new CitationExtractor();
        extractor.stripDelta("trailing ref [12");

        String leftover = extractor.flushRemaining();

        assertThat(leftover).isEqualTo("[12");
        assertThat(extractor.flushRemaining()).isEmpty();
    }

    @Test
    void extractCitationsResolvesKnownMarkersInOrderOfAppearance() {
        UUID doc1 = UUID.randomUUID();
        UUID chunk1 = UUID.randomUUID();
        UUID doc2 = UUID.randomUUID();
        UUID chunk2 = UUID.randomUUID();
        Map<Integer, ChunkReference> refs = Map.of(
                1, new ChunkReference(1, doc1, chunk1, 0.9),
                2, new ChunkReference(2, doc2, chunk2, 0.8));

        var citations = CitationExtractor.extractCitations("First claim. [1] Second claim. [2]", refs);

        assertThat(citations).hasSize(2);
        assertThat(citations.get(0).marker()).isEqualTo(1);
        assertThat(citations.get(0).documentId()).isEqualTo(doc1);
        assertThat(citations.get(1).marker()).isEqualTo(2);
        assertThat(citations.get(1).documentId()).isEqualTo(doc2);
    }

    @Test
    void extractCitationsIgnoresMarkersWithNoMatchingReference() {
        var citations = CitationExtractor.extractCitations("Unsourced claim [99].", Map.of());

        assertThat(citations).isEmpty();
    }

    @Test
    void extractCitationsDeduplicatesRepeatedMarkers() {
        Map<Integer, ChunkReference> refs = Map.of(1, new ChunkReference(1, UUID.randomUUID(), UUID.randomUUID(), 0.9));

        var citations = CitationExtractor.extractCitations("Claim [1]. Same claim again [1].", refs);

        assertThat(citations).hasSize(1);
    }

    @Test
    void extractCitationsQuotesTheSentenceThatCarriedTheMarker() {
        Map<Integer, ChunkReference> refs = Map.of(1, new ChunkReference(1, UUID.randomUUID(), UUID.randomUUID(), 0.9));

        var citations = CitationExtractor.extractCitations("First sentence. pgvector uses cosine distance [1].", refs);

        assertThat(citations.get(0).quotedSpan()).isEqualTo("pgvector uses cosine distance");
    }

    @Test
    void stripAllRemovesEveryMarkerFromFinalText() {
        String stripped = CitationExtractor.stripAll("A [1] and B [2] and C [10].");

        assertThat(stripped).isEqualTo("A  and B  and C .");
    }

    @Test
    void stripAllLeavesTextWithoutMarkersUnchanged() {
        assertThat(CitationExtractor.stripAll("no markers here")).isEqualTo("no markers here");
    }
}
