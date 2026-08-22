package io.github.fragudev.ailab.tools.internal;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.fragudev.ailab.tools.ToolExecutionContext;
import io.github.fragudev.ailab.tools.ToolResult;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

class CalculatorToolTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final CalculatorTool tool = new CalculatorTool();

    @Test
    void evaluatesBasicArithmetic() {
        ToolResult result = execute("12 * 7");

        assertThat(result.success()).isTrue();
        assertThat(resultOf(result)).isEqualTo(84.0);
    }

    @Test
    void respectsOperatorPrecedenceAndParentheses() {
        assertThat(resultOf(execute("2 + 3 * 4"))).isEqualTo(14.0);
        assertThat(resultOf(execute("(2 + 3) * 4"))).isEqualTo(20.0);
        assertThat(resultOf(execute("-5 + 2"))).isEqualTo(-3.0);
    }

    @Test
    void divisionByZeroIsAFailureNotAnException() {
        ToolResult result = execute("1 / 0");

        assertThat(result.success()).isFalse();
        assertThat(result.errorMessage()).contains("division by zero");
    }

    @Test
    void malformedExpressionIsAFailureNotAnException() {
        ToolResult result = execute("2 + ");

        assertThat(result.success()).isFalse();
        assertThat(result.errorMessage()).isNotBlank();
    }

    @Test
    void missingArgumentIsAFailureNotAnException() {
        ToolResult result = tool.execute(ToolExecutionContext.direct(), "{}");

        assertThat(result.success()).isFalse();
    }

    private ToolResult execute(String expression) {
        return tool.execute(ToolExecutionContext.direct(), "{\"expression\":\"%s\"}".formatted(expression));
    }

    private static double resultOf(ToolResult result) {
        JsonNode node = JSON.readTree(result.resultJson());
        return node.path("result").asDouble();
    }
}
