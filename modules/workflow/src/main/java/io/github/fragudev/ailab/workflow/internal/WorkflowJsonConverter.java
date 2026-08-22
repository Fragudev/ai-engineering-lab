package io.github.fragudev.ailab.workflow.internal;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import java.io.IOException;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * A private ObjectMapper (Jackson 2), same reasoning as {@code tools.internal.ToolInvocationJsonConverter}
 * and {@code knowledge.internal.JsonMetadataConverter} — deliberately duplicated per module rather
 * than shared, matching this codebase's existing convention.
 */
@Converter
public class WorkflowJsonConverter implements AttributeConverter<Map<String, Object>, String> {

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
            throw new IllegalArgumentException("Failed to serialize workflow data to JSON", e);
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
            throw new IllegalArgumentException("Failed to deserialize workflow data from JSON", e);
        }
    }
}
