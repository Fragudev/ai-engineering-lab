package io.github.fragudev.ailab;

import io.github.fragudev.ailab.knowledge.HybridSearchOptions;
import io.github.fragudev.ailab.knowledge.HybridSearchService;
import io.github.fragudev.ailab.knowledge.RerankStrategy;
import io.github.fragudev.ailab.knowledge.SearchResult;
import io.github.fragudev.ailab.tools.Tool;
import io.github.fragudev.ailab.tools.ToolDefinition;
import io.github.fragudev.ailab.tools.ToolExecutionContext;
import io.github.fragudev.ailab.tools.ToolResult;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;

/**
 * The "knowledge base search" tool the roadmap names for Phase 5. Lives here, not in {@code tools}
 * — {@code tools} deliberately stays domain-agnostic (docs/architecture.md #3: it doesn't depend on
 * {@code knowledge}), so a thin {@link Tool} adapter over {@link HybridSearchService} is registered
 * from {@code app} instead, the same way {@code ConversationController} already composes {@code rag}
 * and {@code conversation} (see docs/adr/0009-tool-design-and-security-boundaries.md). A fixed
 * retrieval configuration is used deliberately, not a selectable {@code RagProfile} — coupling this
 * tool to {@code rag} would defeat the point of keeping it a thin adapter.
 */
@Component
class KnowledgeBaseSearchTool implements Tool {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int TOP_K = 5;

    /** Inert here — this tool's fixed config never reranks ({@code RerankStrategy.NONE}), so the MMR
     * weight is never read. Present only because {@code HybridSearchOptions} requires the field. */
    private static final double MMR_LAMBDA_UNUSED = 0.7;

    private static final ToolDefinition DEFINITION = new ToolDefinition(
            "knowledge-base-search",
            "1",
            "Searches the ingested knowledge base for chunks relevant to a query.",
            """
            {"$schema":"https://json-schema.org/draft/2020-12/schema","type":"object",\
            "properties":{"query":{"type":"string"}},"required":["query"],\
            "additionalProperties":false}""",
            """
            {"$schema":"https://json-schema.org/draft/2020-12/schema","type":"object",\
            "properties":{"results":{"type":"array"}},"required":["results"]}""",
            Set.of("knowledge-base:search"),
            true,
            false,
            Duration.ofSeconds(10));

    private final HybridSearchService hybridSearchService;

    KnowledgeBaseSearchTool(HybridSearchService hybridSearchService) {
        this.hybridSearchService = hybridSearchService;
    }

    @Override
    public ToolDefinition definition() {
        return DEFINITION;
    }

    @Override
    public ToolResult execute(ToolExecutionContext context, String argumentsJson) {
        String query;
        try {
            JsonNode arguments = JSON.readTree(argumentsJson);
            query = arguments.path("query").asString();
        } catch (RuntimeException e) {
            return ToolResult.failure("Could not read 'query' from arguments: " + e.getMessage());
        }
        if (query == null || query.isBlank()) {
            return ToolResult.failure("'query' must not be blank");
        }

        List<SearchResult> results = hybridSearchService.search(
                query, new HybridSearchOptions(TOP_K, 20, true, RerankStrategy.NONE, MMR_LAMBDA_UNUSED));

        ArrayNode resultsNode = JSON.createArrayNode();
        for (SearchResult result : results) {
            resultsNode.add(JSON.createObjectNode()
                    .put("chunkId", result.chunk().id().toString())
                    .put("documentId", result.chunk().documentId().toString())
                    .put("content", result.chunk().content())
                    .put("score", result.fusedScore()));
        }
        String resultJson = JSON.writeValueAsString(JSON.createObjectNode().set("results", resultsNode));
        return ToolResult.ok(resultJson);
    }
}
