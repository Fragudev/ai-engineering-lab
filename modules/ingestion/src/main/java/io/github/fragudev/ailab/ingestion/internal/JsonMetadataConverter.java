package io.github.fragudev.ailab.ingestion.internal;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * A private ObjectMapper, not the app's autoconfigured Jackson bean — see the identical converter
 * in the {@code knowledge} module for why (Jackson 2/3 coexistence, a still-open Hibernate 7
 * FormatMapper gap for {@code @JdbcTypeCode(SqlTypes.JSON)}).
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
