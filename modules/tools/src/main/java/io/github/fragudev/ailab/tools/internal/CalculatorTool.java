package io.github.fragudev.ailab.tools.internal;

import io.github.fragudev.ailab.tools.Tool;
import io.github.fragudev.ailab.tools.ToolDefinition;
import io.github.fragudev.ailab.tools.ToolExecutionContext;
import io.github.fragudev.ailab.tools.ToolResult;
import java.time.Duration;
import java.util.Set;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Arithmetic over {@code + - * / ( )} and decimals, via a hand-written recursive-descent evaluator
 * — never {@code eval()} or {@code ScriptEngine}, per docs/threat-model.md's no-code-execution rule
 * for tools. Division by zero and malformed expressions are reported as {@link ToolResult} failures,
 * never thrown.
 */
@Component
class CalculatorTool implements Tool {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static final ToolDefinition DEFINITION = new ToolDefinition(
            "calculator",
            "1",
            "Evaluates a basic arithmetic expression (+, -, *, /, parentheses).",
            """
            {"$schema":"https://json-schema.org/draft/2020-12/schema","type":"object",\
            "properties":{"expression":{"type":"string"}},"required":["expression"],\
            "additionalProperties":false}""",
            """
            {"$schema":"https://json-schema.org/draft/2020-12/schema","type":"object",\
            "properties":{"result":{"type":"number"}},"required":["result"]}""",
            Set.of("calculator:use"),
            false,
            false,
            Duration.ofSeconds(5));

    @Override
    public ToolDefinition definition() {
        return DEFINITION;
    }

    @Override
    public ToolResult execute(ToolExecutionContext context, String argumentsJson) {
        String expression;
        try {
            JsonNode arguments = JSON.readTree(argumentsJson);
            expression = arguments.path("expression").asString();
        } catch (RuntimeException e) {
            return ToolResult.failure("Could not read 'expression' from arguments: " + e.getMessage());
        }
        if (expression == null || expression.isBlank()) {
            return ToolResult.failure("'expression' must not be blank");
        }

        try {
            double result = new Evaluator(expression).evaluate();
            return ToolResult.ok(JSON.writeValueAsString(JSON.createObjectNode().put("result", result)));
        } catch (ArithmeticException | IllegalArgumentException e) {
            return ToolResult.failure("Could not evaluate '%s': %s".formatted(expression, e.getMessage()));
        }
    }

    /** {@code expression := term (('+' | '-') term)*}, {@code term := factor (('*' | '/') factor)*},
     * {@code factor := '-'? (NUMBER | '(' expression ')')}. */
    private static final class Evaluator {

        private final String input;
        private int pos;

        Evaluator(String input) {
            this.input = input;
        }

        double evaluate() {
            double result = parseExpression();
            skipWhitespace();
            if (pos < input.length()) {
                throw new IllegalArgumentException("Unexpected character at position " + pos);
            }
            return result;
        }

        private double parseExpression() {
            double value = parseTerm();
            while (true) {
                skipWhitespace();
                if (peek() == '+') {
                    pos++;
                    value += parseTerm();
                } else if (peek() == '-') {
                    pos++;
                    value -= parseTerm();
                } else {
                    return value;
                }
            }
        }

        private double parseTerm() {
            double value = parseFactor();
            while (true) {
                skipWhitespace();
                if (peek() == '*') {
                    pos++;
                    value *= parseFactor();
                } else if (peek() == '/') {
                    pos++;
                    double divisor = parseFactor();
                    if (divisor == 0.0) {
                        throw new ArithmeticException("division by zero");
                    }
                    value /= divisor;
                } else {
                    return value;
                }
            }
        }

        private double parseFactor() {
            skipWhitespace();
            if (peek() == '-') {
                pos++;
                return -parseFactor();
            }
            if (peek() == '(') {
                pos++;
                double value = parseExpression();
                skipWhitespace();
                if (peek() != ')') {
                    throw new IllegalArgumentException("Expected ')' at position " + pos);
                }
                pos++;
                return value;
            }
            return parseNumber();
        }

        private double parseNumber() {
            skipWhitespace();
            int start = pos;
            while (pos < input.length() && (Character.isDigit(input.charAt(pos)) || input.charAt(pos) == '.')) {
                pos++;
            }
            if (start == pos) {
                throw new IllegalArgumentException("Expected a number at position " + pos);
            }
            try {
                return Double.parseDouble(input.substring(start, pos));
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("Malformed number at position " + start);
            }
        }

        private void skipWhitespace() {
            while (pos < input.length() && Character.isWhitespace(input.charAt(pos))) {
                pos++;
            }
        }

        private char peek() {
            return pos < input.length() ? input.charAt(pos) : '\0';
        }
    }
}
