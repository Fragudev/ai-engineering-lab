package io.github.fragudev.ailab.ingestion.internal;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.TopicPartition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaOperations;
import org.springframework.kafka.listener.ConsumerRecordRecoverer;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.stereotype.Component;

/**
 * One generic recoverer for all three pipeline stages. Boot's default listener setup only converts
 * the raw Kafka record into the typed event ({@code EventEnvelope}/{@code DocumentScoped}) at
 * {@code @KafkaListener} parameter binding; the error-handling path this recoverer runs on sees the
 * record's raw JSON bytes, so {@code documentId}/{@code correlationId}/{@code eventId} are pulled
 * out by field name instead — every one of the 3 triggering event types names them identically
 * ({@link DocumentScoped}, {@code EventEnvelope}), so this stays generic across all three without a
 * per-stage recoverer or per-stage listener container factory.
 */
@Component
class IngestionFailureRecoverer implements ConsumerRecordRecoverer {

    private static final Logger log = LoggerFactory.getLogger(IngestionFailureRecoverer.class);

    private final FailureRecording failureRecording;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final DeadLetterPublishingRecoverer dltRecoverer;

    IngestionFailureRecoverer(FailureRecording failureRecording, KafkaOperations<Object, Object> kafkaOperations) {
        this.failureRecording = failureRecording;
        this.dltRecoverer = new DeadLetterPublishingRecoverer(
                kafkaOperations, (record, ex) -> new TopicPartition(record.topic() + ".dlt", record.partition()));
    }

    @Override
    public void accept(ConsumerRecord<?, ?> record, Exception exception) {
        if (record.value() instanceof byte[] rawEvent) {
            try {
                JsonNode json = objectMapper.readTree(rawEvent);
                failureRecording.recordFailure(
                        UUID.fromString(json.get("correlationId").asText()),
                        UUID.fromString(json.get("eventId").asText()),
                        UUID.fromString(json.get("documentId").asText()),
                        record.topic(),
                        exception);
            } catch (Exception parseFailure) {
                log.error(
                        "Could not parse failed record from topic {} to record job failure; the message is still"
                                + " being dead-lettered",
                        record.topic(),
                        parseFailure);
            }
        }
        dltRecoverer.accept(record, exception);
    }
}
