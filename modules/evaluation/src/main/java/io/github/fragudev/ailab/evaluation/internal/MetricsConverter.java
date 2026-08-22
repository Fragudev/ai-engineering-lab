package io.github.fragudev.ailab.evaluation.internal;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * A private ObjectMapper, not the app's autoconfigured Jackson bean — same reasoning and pattern as
 * {@code knowledge.internal.JsonMetadataConverter} (Phase 2/3): sidesteps a still-open Hibernate 7 /
 * Jackson 3 gap where {@code @JdbcTypeCode(SqlTypes.JSON)}'s automatic FormatMapper detection can
 * fail to find a mapper at all.
 */
@Converter
public class MetricsConverter implements AttributeConverter<Map<String, Object>, String> {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

    @Override
    public @Nullable String convertToDatabaseColumn(@Nullable Map<String, Object> attribute) {
        if (attribute == null) {
            return null;
        }
        try {
            return MAPPER.writeValueAsString(attribute);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalArgumentException("Failed to serialize metrics to JSON", e);
        }
    }

    @Override
    public @Nullable Map<String, Object> convertToEntityAttribute(@Nullable String dbData) {
        if (dbData == null) {
            return null;
        }
        try {
            return MAPPER.readValue(dbData, MAP_TYPE);
        } catch (java.io.IOException e) {
            throw new IllegalArgumentException("Failed to deserialize metrics from JSON", e);
        }
    }
}
