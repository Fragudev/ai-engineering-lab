package io.github.fragudev.ailab.knowledge.internal;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * A private ObjectMapper, not the app's autoconfigured Jackson bean, so this stays correct
 * regardless of which Jackson generation (2.x/3.x, both present in Spring Boot 4.1's dependency
 * management) the rest of the app happens to wire — and sidesteps a still-open Hibernate 7 /
 * Jackson 3 gap where {@code @JdbcTypeCode(SqlTypes.JSON)}'s automatic FormatMapper detection can
 * fail to find a mapper at all.
 */
@Converter
public class JsonMetadataConverter implements AttributeConverter<Map<String, Object>, String> {

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
            throw new IllegalArgumentException("Failed to serialize metadata to JSON", e);
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
            throw new IllegalArgumentException("Failed to deserialize metadata from JSON", e);
        }
    }
}
