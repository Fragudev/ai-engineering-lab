package io.github.fragudev.ailab.tools.internal;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import java.io.IOException;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * A private ObjectMapper (Jackson 2, not the Jackson 3 this module also carries for schema
 * validation) — same reasoning as {@code knowledge.internal.JsonMetadataConverter}: this stays
 * correct regardless of which Jackson generation the rest of the app wires, and sidesteps a still-
 * open Hibernate 7 / Jackson 3 gap where {@code @JdbcTypeCode(SqlTypes.JSON)}'s automatic
 * FormatMapper detection can fail. Deliberately duplicated per module rather than shared, matching
 * this codebase's existing convention (the same class already exists twice, in {@code knowledge}
 * and {@code ingestion}).
 */
@Converter
public class ToolInvocationJsonConverter implements AttributeConverter<Map<String, Object>, String> {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

    @Override
    public @Nullable String convertToDatabaseColumn(@Nullable Map<String, Object> attribute) {
        if (attribute == null) {
            return null;
        }
        try {
            return MAPPER.writeValueAsString(attribute);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Failed to serialize tool invocation data to JSON", e);
        }
    }

    @Override
    public @Nullable Map<String, Object> convertToEntityAttribute(@Nullable String dbData) {
        if (dbData == null) {
            return null;
        }
        try {
            return MAPPER.readValue(dbData, MAP_TYPE);
        } catch (IOException e) {
            throw new IllegalArgumentException("Failed to deserialize tool invocation data from JSON", e);
        }
    }
}
