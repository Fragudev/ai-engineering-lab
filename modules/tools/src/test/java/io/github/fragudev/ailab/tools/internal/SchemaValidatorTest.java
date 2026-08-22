package io.github.fragudev.ailab.tools.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class SchemaValidatorTest {

    private static final String SCHEMA = """
            {"$schema":"https://json-schema.org/draft/2020-12/schema","type":"object",\
            "properties":{"expression":{"type":"string"}},"required":["expression"],\
            "additionalProperties":false}""";

    private final SchemaValidator validator = new SchemaValidator();

    @Test
    void validInstancePasses() {
        List<String> violations = validator.validate(SCHEMA, "{\"expression\":\"1 + 1\"}");

        assertThat(violations).isEmpty();
    }

    @Test
    void missingRequiredFieldIsReported() {
        List<String> violations = validator.validate(SCHEMA, "{}");

        assertThat(violations).isNotEmpty();
    }

    @Test
    void wrongTypeIsReported() {
        List<String> violations = validator.validate(SCHEMA, "{\"expression\":42}");

        assertThat(violations).isNotEmpty();
    }

    @Test
    void additionalPropertyIsReported() {
        List<String> violations = validator.validate(SCHEMA, "{\"expression\":\"1\",\"extra\":true}");

        assertThat(violations).isNotEmpty();
    }

    @Test
    void malformedJsonIsReportedNotThrown() {
        List<String> violations = validator.validate(SCHEMA, "{not json");

        assertThat(violations).isNotEmpty();
    }
}
