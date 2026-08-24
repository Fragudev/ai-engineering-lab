package io.github.fragudev.ailab.shared;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Every typed id in this package ({@code ConversationId}, {@code MessageId}, {@code DocumentId},
 * ...) follows the same {@code generate()}/{@code of()}/{@code toString()} contract — covered here
 * once via a representative sample rather than duplicated per class (post-roadmap review issue #30).
 * None of this needed Postgres or Kafka. */
class TypedIdTest {

    @Test
    void generateProducesDistinctNonNullIds() {
        ConversationId first = ConversationId.generate();
        ConversationId second = ConversationId.generate();

        assertThat(first).isNotNull();
        assertThat(first.value()).isNotNull();
        assertThat(first).isNotEqualTo(second);
    }

    @Test
    void ofRoundTripsTheUnderlyingUuidExactly() {
        UUID raw = UUID.randomUUID();

        ConversationId id = ConversationId.of(raw);

        assertThat(id.value()).isEqualTo(raw);
    }

    @Test
    void toStringIsTheUnderlyingUuidsStringForm() {
        UUID raw = UUID.randomUUID();

        ConversationId id = ConversationId.of(raw);

        assertThat(id.toString()).isEqualTo(raw.toString());
    }

    @Test
    void twoIdsOfTheSameTypeWrappingTheSameUuidAreEqual() {
        UUID raw = UUID.randomUUID();

        assertThat(ConversationId.of(raw)).isEqualTo(ConversationId.of(raw));
        assertThat(ConversationId.of(raw)).hasSameHashCodeAs(ConversationId.of(raw));
    }

    @Test
    void twoIdsOfDifferentTypesWrappingTheSameUuidAreNotEqual() {
        // The entire point of a typed id (per every one of these classes' own javadoc): a
        // ConversationId and a MessageId built from the same raw UUID must never be interchangeable.
        UUID raw = UUID.randomUUID();

        ConversationId conversationId = ConversationId.of(raw);
        MessageId messageId = MessageId.of(raw);

        assertThat((Object) conversationId).isNotEqualTo(messageId);
    }

    @Test
    void everyTypedIdInThisPackageFollowsTheSameContract() {
        assertRoundTrips(DocumentId::of, DocumentId::generate, DocumentId::value);
        assertRoundTrips(MessageId::of, MessageId::generate, MessageId::value);
        assertRoundTrips(CitationId::of, CitationId::generate, CitationId::value);
        assertRoundTrips(IngestionJobId::of, IngestionJobId::generate, IngestionJobId::value);
        assertRoundTrips(ToolInvocationId::of, ToolInvocationId::generate, ToolInvocationId::value);
        assertRoundTrips(WorkflowRunId::of, WorkflowRunId::generate, WorkflowRunId::value);
        assertRoundTrips(WorkflowStepId::of, WorkflowStepId::generate, WorkflowStepId::value);
        assertRoundTrips(EvalDatasetId::of, EvalDatasetId::generate, EvalDatasetId::value);
        assertRoundTrips(EvalCaseId::of, EvalCaseId::generate, EvalCaseId::value);
        assertRoundTrips(EvalRunId::of, EvalRunId::generate, EvalRunId::value);
        assertRoundTrips(EvalResultId::of, EvalResultId::generate, EvalResultId::value);
    }

    private static <T> void assertRoundTrips(
            java.util.function.Function<UUID, T> of,
            java.util.function.Supplier<T> generate,
            java.util.function.Function<T, UUID> value) {
        UUID raw = UUID.randomUUID();
        assertThat(value.apply(of.apply(raw))).isEqualTo(raw);
        assertThat(value.apply(generate.get())).isNotNull();
        assertThat(generate.get()).isNotEqualTo(generate.get());
    }
}
