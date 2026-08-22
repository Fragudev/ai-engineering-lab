package io.github.fragudev.ailab;

import io.github.fragudev.ailab.rag.RetrievalTrace;
import java.util.List;

record RetrievalTraceResponse(
        String originalQuery, String normalizedQuery, String ragProfile, List<SearchResultResponse> results) {

    static RetrievalTraceResponse from(RetrievalTrace trace) {
        return new RetrievalTraceResponse(
                trace.originalQuery(),
                trace.normalizedQuery(),
                trace.profile().name(),
                trace.results().stream().map(SearchResultResponse::from).toList());
    }
}
