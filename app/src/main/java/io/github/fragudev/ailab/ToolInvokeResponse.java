package io.github.fragudev.ailab;

import io.github.fragudev.ailab.tools.ToolResult;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.ObjectMapper;

/** {@code result} is a plain deserialized {@code Map}/{@code List}/primitive structure, not a
 * schema-library or Jackson-generation-specific type — Spring's own HTTP message converter (whatever
 * Jackson generation it's autoconfigured with) can serialize it without needing to match the
 * generation this class parsed it with. */
record ToolInvokeResponse(
        boolean success, @Nullable Object result, @Nullable String errorMessage) {

    private static final ObjectMapper JSON = new ObjectMapper();

    static ToolInvokeResponse from(ToolResult result) {
        Object parsedResult = result.resultJson() == null ? null : JSON.readValue(result.resultJson(), Object.class);
        return new ToolInvokeResponse(result.success(), parsedResult, result.errorMessage());
    }
}
