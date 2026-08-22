package io.github.fragudev.ailab.tools.internal;

import com.networknt.schema.Error;
import com.networknt.schema.InputFormat;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import java.util.List;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Wraps {@code com.networknt:json-schema-validator} (draft 2020-12), exposing only {@code String}
 * across its boundary — never a schema-library or Jackson type — so nothing outside this class has
 * to know it's Jackson 3 under the hood (mirrors how {@code Chunk.metadata} and
 * {@code FixtureCase.response} already keep a Jackson-generation choice contained to one file).
 * These messages are what acceptance-criterion 1 means by "a structured error the model can act
 * on" — fed back into the tool-calling loop as a {@code TOOL}-role message.
 */
@Component
public class SchemaValidator {

    private static final SchemaRegistry REGISTRY =
            SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12);
    private static final ObjectMapper JSON = new ObjectMapper();

    /** @return the validation error messages; empty means {@code instanceJson} is valid */
    public List<String> validate(String schemaJson, String instanceJson) {
        Schema schema = REGISTRY.getSchema(schemaJson, InputFormat.JSON);
        JsonNode instance;
        try {
            instance = JSON.readTree(instanceJson);
        } catch (JacksonException e) {
            return List.of("Arguments are not valid JSON: " + e.getMessage());
        }
        List<Error> errors = schema.validate(instance);
        return errors.stream().map(Error::getMessage).toList();
    }
}
