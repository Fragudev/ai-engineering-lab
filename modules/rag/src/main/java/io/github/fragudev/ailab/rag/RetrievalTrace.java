package io.github.fragudev.ailab.rag;

import io.github.fragudev.ailab.knowledge.SearchResult;
import java.util.List;

/**
 * The debug view into the retrieval pipeline ({@code POST /api/v1/retrieval:search},
 * docs/architecture.md #5) — every {@link SearchResult} already carries its own
 * vector/lexical/fused/rerank scores, so this is mostly a wrapper naming what query produced them.
 */
public record RetrievalTrace(
        String originalQuery, String normalizedQuery, RagProfile profile, List<SearchResult> results) {}
