package io.github.fragudev.ailab.workflow.internal;

import io.github.fragudev.ailab.knowledge.SearchResult;
import io.github.fragudev.ailab.rag.RagPipeline;
import io.github.fragudev.ailab.rag.RagProfiles;
import io.github.fragudev.ailab.rag.RetrievalTrace;
import io.github.fragudev.ailab.shared.WorkflowRunId;
import io.github.fragudev.ailab.workflow.WorkflowRunStatus;
import io.github.fragudev.ailab.workflow.WorkflowStepStatus;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * The documentation-research state machine (docs/roadmap.md, Phase 6): {@code plan-sub-queries} →
 * {@code retrieve} → {@code extract-per-source} → {@code synthesise} → {@code self-check} →
 * {@code answer}. {@link #run(WorkflowRunId)} is idempotent by design — it loads whatever
 * {@code WorkflowStep} rows already exist for the run and continues from the first one that isn't
 * {@code SUCCEEDED}, so the exact same entry point serves a brand-new run and a resumed one
 * (docs/adr/0010-agent-orchestration.md).
 */
@Component
public class DocumentationResearchEngine {

    private static final Logger log = LoggerFactory.getLogger(DocumentationResearchEngine.class);

    private final WorkflowRunRepository runRepository;
    private final WorkflowStepRepository stepRepository;
    private final StageRunner stageRunner;
    private final WorkflowsProperties properties;
    private final WorkflowMetrics metrics;
    private final ExecutorService executor;
    private final RagPipeline ragPipeline;
    private final SubQueryPlanner subQueryPlanner;
    private final SourceExtractor sourceExtractor;
    private final AnswerSynthesiser answerSynthesiser;
    private final CitationChecker citationChecker;

    DocumentationResearchEngine(
            WorkflowRunRepository runRepository,
            WorkflowStepRepository stepRepository,
            StageRunner stageRunner,
            WorkflowsProperties properties,
            WorkflowMetrics metrics,
            ExecutorService executor,
            RagPipeline ragPipeline,
            SubQueryPlanner subQueryPlanner,
            SourceExtractor sourceExtractor,
            AnswerSynthesiser answerSynthesiser,
            CitationChecker citationChecker) {
        this.runRepository = runRepository;
        this.stepRepository = stepRepository;
        this.stageRunner = stageRunner;
        this.properties = properties;
        this.metrics = metrics;
        this.executor = executor;
        this.ragPipeline = ragPipeline;
        this.subQueryPlanner = subQueryPlanner;
        this.sourceExtractor = sourceExtractor;
        this.answerSynthesiser = answerSynthesiser;
        this.citationChecker = citationChecker;
    }

    public void run(WorkflowRunId runId) {
        WorkflowRun run = runRepository
                .findById(runId.value())
                .orElseThrow(() -> new NoSuchElementException("Workflow run not found: " + runId));
        if (run.status() == WorkflowRunStatus.SUCCEEDED || run.status() == WorkflowRunStatus.FAILED) {
            return;
        }
        run.markRunning();
        runRepository.save(run);

        Map<String, WorkflowStep> byName = new LinkedHashMap<>();
        for (WorkflowStep step : stepRepository.findByRun(runId)) {
            byName.put(step.name(), step);
        }

        String query = (String) run.input().get("query");
        LlmCallBudget budget = new LlmCallBudget(properties.maxLlmCallsPerRun());

        try {
            Map<String, Object> planOutput = executeStage(
                    runId, byName, 0, "plan-sub-queries", Map.of("query", query), () -> planStage(query, budget));
            @SuppressWarnings("unchecked")
            List<String> subQueries = (List<String>) planOutput.get("subQueries");

            Map<String, Object> retrieveOutput = executeStage(
                    runId, byName, 1, "retrieve", Map.of("subQueries", subQueries), () -> retrieveStage(subQueries));
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> sources = (List<Map<String, Object>>) retrieveOutput.get("sources");

            Map<String, Object> extractOutput = executeStage(
                    runId,
                    byName,
                    2,
                    "extract-per-source",
                    Map.of("query", query, "sources", sources),
                    () -> extractStage(query, sources, budget));
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> extracted = (List<Map<String, Object>>) extractOutput.get("extracted");

            Map<String, Object> synthesiseOutput = executeStage(
                    runId,
                    byName,
                    3,
                    "synthesise",
                    Map.of("query", query, "extracted", extracted),
                    () -> synthesiseStage(query, extracted, budget));

            executeStage(runId, byName, 4, "self-check", synthesiseOutput, () -> selfCheckStage(synthesiseOutput));

            Map<String, Object> answerOutput = executeStage(
                    runId, byName, 5, "answer", synthesiseOutput, () -> answerStage(runId, synthesiseOutput));

            run.markSucceeded(answerOutput);
            runRepository.save(run);
            metrics.recordRun(run.type(), WorkflowRunStatus.SUCCEEDED);
        } catch (StageFailedException e) {
            String reason = e.getCause() == null
                    ? e.getMessage()
                    : String.valueOf(e.getCause().getMessage());
            run.markFailed(Map.of("failedStage", e.stageName(), "reason", String.valueOf(reason)));
            runRepository.save(run);
            metrics.recordRun(run.type(), WorkflowRunStatus.FAILED);
            log.warn("Workflow run {} compensated: stage '{}' failed", runId, e.stageName(), e);
        }
    }

    private Map<String, Object> executeStage(
            WorkflowRunId runId,
            Map<String, WorkflowStep> byName,
            int stepIndex,
            String name,
            Map<String, Object> input,
            StageFunction function) {
        WorkflowStep existing = byName.get(name);
        if (existing != null && existing.status() == WorkflowStepStatus.SUCCEEDED) {
            Map<String, Object> output = existing.output();
            return output == null ? Map.of() : output;
        }
        return stageRunner.run(runId, stepIndex, name, input, existing, function);
    }

    private StageOutcome planStage(String query, LlmCallBudget budget) {
        budget.consume();
        SubQueryPlanner.PlanResult result = subQueryPlanner.plan(query, properties.maxSubQueries());
        return StageOutcome.of(Map.of("subQueries", result.subQueries()), result.costUsd());
    }

    private StageOutcome retrieveStage(List<String> subQueries) throws Exception {
        List<Future<RetrievalTrace>> futures = subQueries.stream()
                .map(subQuery -> executor.submit(() -> ragPipeline.search(subQuery, RagProfiles.HYBRID)))
                .toList();

        List<Map<String, Object>> subQueryResults = new ArrayList<>();
        LinkedHashMap<UUID, SearchResult> byChunkId = new LinkedHashMap<>();
        for (int i = 0; i < futures.size(); i++) {
            String subQuery = subQueries.get(i);
            try {
                RetrievalTrace trace =
                        futures.get(i).get(properties.stepTimeout().toMillis(), TimeUnit.MILLISECONDS);
                for (SearchResult result : trace.results()) {
                    byChunkId.putIfAbsent(result.chunk().id(), result);
                }
                subQueryResults.add(Map.of(
                        "subQuery", subQuery, "resultCount", trace.results().size()));
            } catch (Exception e) {
                log.warn("Retrieval failed for sub-query '{}'", subQuery, e);
                subQueryResults.add(Map.of("subQuery", subQuery, "error", String.valueOf(e.getMessage())));
            }
        }

        if (byChunkId.isEmpty()) {
            throw new IllegalStateException("Every sub-query retrieval failed; nothing to extract from");
        }

        List<Map<String, Object>> sources = byChunkId.values().stream()
                .sorted(Comparator.comparingDouble(SearchResult::fusedScore).reversed())
                .limit(properties.maxSourcesToExtract())
                .map(result -> Map.<String, Object>of(
                        "chunkId", result.chunk().id().toString(),
                        "documentId", result.chunk().documentId().toString(),
                        "content", result.chunk().content()))
                .toList();
        return StageOutcome.of(Map.of("subQueryResults", subQueryResults, "sources", sources));
    }

    private StageOutcome extractStage(String query, List<Map<String, Object>> sources, LlmCallBudget budget)
            throws Exception {
        List<Future<SourceExtractor.ExtractResult>> futures = sources.stream()
                .map(source -> executor.submit(() -> {
                    budget.consume();
                    return sourceExtractor.extract(query, (String) source.get("content"));
                }))
                .toList();

        List<Map<String, Object>> extracted = new ArrayList<>();
        BigDecimal totalCost = BigDecimal.ZERO;
        for (int i = 0; i < futures.size(); i++) {
            Map<String, Object> source = sources.get(i);
            try {
                SourceExtractor.ExtractResult result =
                        futures.get(i).get(properties.stepTimeout().toMillis(), TimeUnit.MILLISECONDS);
                totalCost = totalCost.add(result.costUsd());
                if (result.facts() != null) {
                    extracted.add(Map.of(
                            "chunkId", source.get("chunkId"),
                            "documentId", source.get("documentId"),
                            "facts", result.facts()));
                }
            } catch (Exception e) {
                log.warn("Source extraction failed for chunk {}", source.get("chunkId"), e);
            }
        }

        if (extracted.isEmpty()) {
            throw new IllegalStateException("Every source extraction failed or found nothing relevant");
        }
        return StageOutcome.of(Map.of("extracted", extracted), totalCost);
    }

    private StageOutcome synthesiseStage(String query, List<Map<String, Object>> extractedMaps, LlmCallBudget budget) {
        List<ExtractedSource> sources = new ArrayList<>();
        for (int i = 0; i < extractedMaps.size(); i++) {
            Map<String, Object> source = extractedMaps.get(i);
            sources.add(new ExtractedSource(
                    UUID.fromString((String) source.get("chunkId")),
                    UUID.fromString((String) source.get("documentId")),
                    i + 1,
                    (String) source.get("facts")));
        }

        budget.consume();
        AnswerSynthesiser.SynthesisResult first = answerSynthesiser.synthesise(query, sources, null);
        BigDecimal totalCost = first.costUsd();
        String answer = first.answer();

        Set<Integer> invalid = citationChecker.invalidMarkers(answer, sources.size());
        if (!invalid.isEmpty()) {
            budget.consume();
            String corrective = ("Your previous answer cited source marker(s) %s that don't exist. Use only "
                            + "markers 1 through %d.")
                    .formatted(invalid, sources.size());
            AnswerSynthesiser.SynthesisResult retry = answerSynthesiser.synthesise(query, sources, corrective);
            totalCost = totalCost.add(retry.costUsd());
            answer = retry.answer();
        }

        List<Map<String, Object>> citations = sources.stream()
                .map(source -> Map.<String, Object>of(
                        "marker", source.marker(),
                        "chunkId", source.chunkId().toString(),
                        "documentId", source.documentId().toString()))
                .toList();
        return StageOutcome.of(Map.of("answer", answer, "citations", citations), totalCost);
    }

    private StageOutcome selfCheckStage(Map<String, Object> synthesiseOutput) {
        String answer = (String) synthesiseOutput.get("answer");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> citations = (List<Map<String, Object>>) synthesiseOutput.get("citations");
        Set<Integer> invalid = citationChecker.invalidMarkers(answer, citations.size());
        if (!invalid.isEmpty()) {
            throw new IllegalStateException("Synthesised answer cites unknown source marker(s): " + invalid);
        }
        return StageOutcome.of(Map.of("valid", true));
    }

    private StageOutcome answerStage(WorkflowRunId runId, Map<String, Object> synthesiseOutput) {
        BigDecimal totalCost = stepRepository.findByRun(runId).stream()
                .map(WorkflowStep::costUsd)
                .filter(Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        Map<String, Object> output = Map.of(
                "answer", synthesiseOutput.get("answer"),
                "citations", synthesiseOutput.get("citations"),
                "totalCostUsd", totalCost);
        return StageOutcome.of(output);
    }
}
