package io.github.fragudev.ailab.evaluation;

import io.github.fragudev.ailab.evaluation.internal.AbstentionMetrics;
import io.github.fragudev.ailab.evaluation.internal.CitationMetrics;
import io.github.fragudev.ailab.evaluation.internal.DatasetLoader;
import io.github.fragudev.ailab.evaluation.internal.EvalResultRepository;
import io.github.fragudev.ailab.evaluation.internal.EvalRunRepository;
import io.github.fragudev.ailab.evaluation.internal.GoldChunkResolver;
import io.github.fragudev.ailab.evaluation.internal.LatencyStats;
import io.github.fragudev.ailab.evaluation.internal.LlmJudge;
import io.github.fragudev.ailab.evaluation.internal.RepeatedMetric;
import io.github.fragudev.ailab.evaluation.internal.ReportWriter;
import io.github.fragudev.ailab.evaluation.internal.RetrievalMetrics;
import io.github.fragudev.ailab.knowledge.SearchResult;
import io.github.fragudev.ailab.rag.RagAnswer;
import io.github.fragudev.ailab.rag.RagAnswerChunk;
import io.github.fragudev.ailab.rag.RagPipeline;
import io.github.fragudev.ailab.rag.RagProfile;
import io.github.fragudev.ailab.rag.RagProfiles;
import io.github.fragudev.ailab.shared.EvalResultId;
import io.github.fragudev.ailab.shared.EvalRunId;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Runs the golden dataset for real against the running system's own {@link RagPipeline} — no
 * mocking, the same "verify against real conditions" discipline as every other module here. See
 * docs/roadmap.md Phase 4 and docs/ai-evaluation.md.
 */
@Service
public class EvalRunner {

    private static final Logger log = LoggerFactory.getLogger(EvalRunner.class);

    private final DatasetLoader datasetLoader;
    private final GoldChunkResolver goldChunkResolver;
    private final RagPipeline ragPipeline;
    private final LlmJudge llmJudge;
    private final EvalRunRepository runRepository;
    private final EvalResultRepository resultRepository;
    private final ReportWriter reportWriter;

    public EvalRunner(
            DatasetLoader datasetLoader,
            GoldChunkResolver goldChunkResolver,
            RagPipeline ragPipeline,
            LlmJudge llmJudge,
            EvalRunRepository runRepository,
            EvalResultRepository resultRepository,
            ReportWriter reportWriter) {
        this.datasetLoader = datasetLoader;
        this.goldChunkResolver = goldChunkResolver;
        this.ragPipeline = ragPipeline;
        this.llmJudge = llmJudge;
        this.runRepository = runRepository;
        this.resultRepository = resultRepository;
        this.reportWriter = reportWriter;
    }

    /** Runs the dataset and writes the Markdown report, without exposing
     * {@code evaluation.internal.ReportWriter} to callers outside this module (e.g. {@code app}'s
     * CLI entry point). {@code recordedProfile} controls whether the report labels its own numbers
     * as mechanism-only (see {@code ReportWriter}). Returns the written file's path. */
    public Path runAndWriteReport(EvalRunConfig config, Path reportsDir, boolean recordedProfile) {
        EvalReport report = run(config);
        return reportWriter.write(report, reportsDir, recordedProfile);
    }

    public EvalReport run(EvalRunConfig config) {
        EvalDataset dataset = datasetLoader.load(config.datasetPath());
        List<EvalCase> cases = datasetLoader.casesFor(dataset);
        if (cases.isEmpty()) {
            throw new IllegalStateException("Dataset at " + config.datasetPath() + " has no cases");
        }

        List<ProfileSummary> summaries = new ArrayList<>();
        String modelUsed = "unknown";
        for (String profileName : config.ragProfiles()) {
            RagProfile profile = RagProfiles.byName(profileName)
                    .orElseThrow(() -> new IllegalArgumentException("Unknown ragProfile: " + profileName));
            ProfileSummary summary = runProfile(dataset, cases, profile, config);
            summaries.add(summary);
            modelUsed = firstModelUsed(summary).orElse(modelUsed);
        }
        return new EvalReport(dataset, config, summaries, modelUsed, Instant.now());
    }

    private ProfileSummary runProfile(
            EvalDataset dataset, List<EvalCase> cases, RagProfile profile, EvalRunConfig config) {
        List<List<CaseResult>> repetitions = new ArrayList<>();
        for (int rep = 0; rep < config.repetitions(); rep++) {
            EvalRun run = runRepository.save(
                    new EvalRun(EvalRunId.generate(), dataset.id(), profile.name(), "pending", config.hardware()));

            List<CaseResult> outcomes = new ArrayList<>();
            for (EvalCase evalCase : cases) {
                CaseResult result;
                try {
                    result = runCase(evalCase, profile, config.runJudge());
                } catch (RuntimeException e) {
                    // A live provider (e.g. lmstudio) is a real, external fault boundary: a single slow or
                    // unresponsive model call must not discard every other case's already-collected results.
                    // Confirmed live against issue #29 — the same run repeatedly died on one hung case after
                    // successfully completing a dozen others.
                    log.warn(
                            "Case '{}' failed for profile '{}', skipping: {}",
                            evalCase.caseKey(),
                            profile.name(),
                            e.toString());
                    continue;
                }
                outcomes.add(result);
                resultRepository.save(new EvalResult(
                        EvalResultId.generate(),
                        run.id(),
                        evalCase.id(),
                        result.answer(),
                        result.metrics().toMap()));
            }
            run.markFinished();
            runRepository.save(run);
            repetitions.add(outcomes);
        }
        return summarize(profile, cases.size(), repetitions);
    }

    private CaseResult runCase(EvalCase evalCase, RagProfile profile, boolean runJudge) {
        Set<UUID> goldChunkIds = Arrays.stream(evalCase.goldChunkRefs())
                .map(goldChunkResolver::resolve)
                .flatMap(Optional::stream)
                .collect(Collectors.toSet());

        List<SearchResult> retrieved =
                ragPipeline.search(evalCase.question(), profile).results();
        double recall = RetrievalMetrics.recallAtK(retrieved, goldChunkIds);
        double mrr = RetrievalMetrics.reciprocalRank(retrieved, goldChunkIds);

        Instant start = Instant.now();
        RagAnswerChunk lastChunk =
                ragPipeline.answer(List.of(), evalCase.question(), profile).blockLast();
        Duration latency = Duration.between(start, Instant.now());
        if (lastChunk == null || lastChunk.aggregate() == null) {
            throw new IllegalStateException(
                    "RagPipeline.answer produced no terminal chunk for case " + evalCase.caseKey());
        }
        RagAnswer answer = lastChunk.aggregate();

        double citationPrecision = CitationMetrics.precision(answer.citations(), goldChunkIds);
        double citationRecall = CitationMetrics.recall(answer.citations(), goldChunkIds);

        Boolean gateAbstained =
                evalCase.category() == EvalCaseCategory.UNANSWERABLE ? AbstentionMetrics.gateAbstained(answer) : null;

        Double judgeCorrectness = null;
        Double judgeFaithfulness = null;
        if (runJudge) {
            var judged = llmJudge.judge(evalCase.question(), evalCase.expectedAnswer(), answer.content());
            judgeCorrectness = judged.correctness();
            judgeFaithfulness = judged.faithfulness();
        }

        CaseMetrics metrics = new CaseMetrics(
                recall,
                mrr,
                citationPrecision,
                citationRecall,
                gateAbstained,
                latency,
                answer.usage().promptTokens(),
                answer.usage().completionTokens(),
                judgeCorrectness,
                judgeFaithfulness);
        return new CaseResult(evalCase, answer.content(), answer.model(), metrics);
    }

    private static ProfileSummary summarize(
            RagProfile profile, int casesPerRepetition, List<List<CaseResult>> repetitions) {
        List<Double> recallSamples = new ArrayList<>();
        List<Double> mrrSamples = new ArrayList<>();
        List<Double> precisionSamples = new ArrayList<>();
        List<Double> citationRecallSamples = new ArrayList<>();
        List<Double> gateAbstentionSamples = new ArrayList<>();
        List<Double> refusalCorrectnessSamples = new ArrayList<>();
        List<Duration> latencies = new ArrayList<>();
        long promptTokens = 0;
        long completionTokens = 0;

        for (List<CaseResult> repetition : repetitions) {
            recallSamples.add(meanOf(repetition, r -> r.metrics().recallAtK()));
            mrrSamples.add(meanOf(repetition, r -> r.metrics().reciprocalRank()));
            precisionSamples.add(meanOf(repetition, r -> r.metrics().citationPrecision()));
            citationRecallSamples.add(meanOf(repetition, r -> r.metrics().citationRecall()));

            // Both figures are computed over UNANSWERABLE cases only — gateAbstained is null for
            // every other category, which is what identifies them here.
            List<CaseResult> unanswerable = repetition.stream()
                    .filter(r -> r.metrics().gateAbstained() != null)
                    .toList();
            if (!unanswerable.isEmpty()) {
                gateAbstentionSamples.add(unanswerable.stream()
                                .filter(r -> Boolean.TRUE.equals(r.metrics().gateAbstained()))
                                .count()
                        / (double) unanswerable.size());

                // Judge-scored, so absent unless --judge ran. Left out of the sample list entirely
                // rather than averaged in as zero: "not measured" and "declined incorrectly" are
                // different claims, and RepeatedMetric renders an empty list as NaN -> "n/a".
                List<Double> judged = unanswerable.stream()
                        .map(r -> r.metrics().judgeCorrectness())
                        .filter(Objects::nonNull)
                        .toList();
                if (!judged.isEmpty()) {
                    refusalCorrectnessSamples.add(judged.stream()
                            .mapToDouble(Double::doubleValue)
                            .average()
                            .orElse(Double.NaN));
                }
            }

            repetition.forEach(r -> latencies.add(r.metrics().latency()));
            promptTokens += repetition.stream()
                    .mapToLong(r -> r.metrics().promptTokens())
                    .sum();
            completionTokens += repetition.stream()
                    .mapToLong(r -> r.metrics().completionTokens())
                    .sum();
        }

        // Every metric above is a mean over the results actually collected. A case that threw was
        // skipped in runProfile (log.warn + continue), so completedCases < casesPerRepetition ×
        // repetitions means those means are a subsample — recorded here so the report can say so
        // instead of presenting a degraded run as a clean one (issues #65, #67).
        int attemptedCases = casesPerRepetition * repetitions.size();
        int completedCases = repetitions.stream().mapToInt(List::size).sum();

        return new ProfileSummary(
                profile.name(),
                RepeatedMetric.of(recallSamples),
                RepeatedMetric.of(mrrSamples),
                RepeatedMetric.of(precisionSamples),
                RepeatedMetric.of(citationRecallSamples),
                RepeatedMetric.of(gateAbstentionSamples),
                RepeatedMetric.of(refusalCorrectnessSamples),
                LatencyStats.of(latencies),
                promptTokens,
                completionTokens,
                new CaseCoverage(attemptedCases, completedCases),
                repetitions);
    }

    private static double meanOf(List<CaseResult> results, java.util.function.ToDoubleFunction<CaseResult> extractor) {
        List<Double> values = results.stream()
                .mapToDouble(extractor)
                .filter(d -> !Double.isNaN(d))
                .boxed()
                .toList();
        return values.isEmpty()
                ? Double.NaN
                : values.stream().mapToDouble(Double::doubleValue).average().orElse(Double.NaN);
    }

    private static Optional<String> firstModelUsed(ProfileSummary summary) {
        return summary.repetitions().stream()
                .flatMap(List::stream)
                .map(CaseResult::modelUsed)
                .findFirst();
    }
}
