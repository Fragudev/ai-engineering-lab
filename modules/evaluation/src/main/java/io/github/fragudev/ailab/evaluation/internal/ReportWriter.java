package io.github.fragudev.ailab.evaluation.internal;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.fragudev.ailab.evaluation.EvalReport;
import io.github.fragudev.ailab.evaluation.ProfileSummary;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Writes the profile comparison as Markdown to {@code eval/reports/}, with reproducibility metadata
 * and a methodology-limitations section — docs/ai-evaluation.md §5, §7 and the roadmap's own
 * acceptance criteria for this phase. Also writes a machine-readable JSON sidecar with the same
 * aggregate numbers, which {@code scripts/check-eval-regression.sh} reads in the nightly workflow
 * (.github/workflows/nightly-eval.yml) — the Markdown table alone isn't something CI should parse.
 */
@Component
public class ReportWriter {

    private static final ObjectMapper JSON = new ObjectMapper();

    public Path write(EvalReport report, Path reportsDir, boolean recordedProfile) {
        try {
            Files.createDirectories(reportsDir);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }

        String profileSlug = report.profiles().stream()
                .map(ProfileSummary::ragProfile)
                .reduce((a, b) -> a + "-" + b)
                .orElse("report");
        String baseName = "%s-%s".formatted(report.generatedAt().toString().substring(0, 10), profileSlug);
        Path path = reportsDir.resolve(baseName + ".md");

        try {
            Files.writeString(path, render(report, recordedProfile));
            Files.writeString(reportsDir.resolve(baseName + ".json"), renderJson(report));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return path;
    }

    private String renderJson(EvalReport report) throws IOException {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("generatedAt", report.generatedAt().toString());
        root.put(
                "dataset",
                Map.of(
                        "name",
                        report.dataset().name(),
                        "version",
                        report.dataset().version()));
        root.put("chatModel", report.chatModel());

        List<Map<String, Object>> profiles =
                report.profiles().stream().map(this::profileToMap).toList();
        root.put("profiles", profiles);

        return JSON.writerWithDefaultPrettyPrinter().writeValueAsString(root);
    }

    private Map<String, Object> profileToMap(ProfileSummary profile) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("ragProfile", profile.ragProfile());
        map.put("recallAtK", metricToMap(profile.recallAtK()));
        map.put("mrr", metricToMap(profile.mrr()));
        map.put("citationPrecision", metricToMap(profile.citationPrecision()));
        map.put("citationRecall", metricToMap(profile.citationRecall()));
        map.put("gateAbstentionRate", metricToMap(profile.gateAbstentionRate()));
        map.put("refusalCorrectness", metricToMap(profile.refusalCorrectness()));
        map.put("latencyP50Ms", profile.latency().p50().toMillis());
        map.put("latencyP95Ms", profile.latency().p95().toMillis());
        map.put("totalPromptTokens", profile.totalPromptTokens());
        map.put("totalCompletionTokens", profile.totalCompletionTokens());
        return map;
    }

    private Map<String, Object> metricToMap(RepeatedMetric metric) {
        // Double.NaN ("undefined", e.g. a metric with no gold refs to average) is not a valid JSON
        // number literal — written as null rather than the non-standard bare NaN token, same
        // omit-invalid-numbers discipline as CaseMetrics.toMap().
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("mean", Double.isNaN(metric.mean()) ? null : metric.mean());
        map.put("spread", metric.spread());
        return map;
    }

    private String render(EvalReport report, boolean recordedProfile) {
        StringBuilder md = new StringBuilder();
        md.append("# Evaluation report\n\n");
        md.append("- **Date:** ").append(report.generatedAt()).append('\n');
        md.append("- **Dataset:** ")
                .append(report.dataset().name())
                .append(" v")
                .append(report.dataset().version())
                .append('\n');
        md.append("- **Chat model:** ").append(report.chatModel()).append('\n');
        md.append("- **Hardware:** ")
                .append(
                        report.config().hardware() != null
                                ? report.config().hardware()
                                : "not measured (no live run in this environment — docs/roadmap.md Phase 4)")
                .append('\n');
        md.append("- **Repetitions per profile:** ")
                .append(report.config().repetitions())
                .append("\n\n");

        md.append("## Profile comparison\n\n");
        md.append("| Profile | Recall@k | MRR | Cite prec. | Cite recall | Gate abstention | Refusal correctness "
                + "| p50 (ms) | p95 (ms) | Prompt tokens | Completion tokens |\n");
        md.append("|---|---|---|---|---|---|---|---|---|---|---|\n");
        for (ProfileSummary profile : report.profiles()) {
            md.append("| ")
                    .append(profile.ragProfile())
                    .append(" | ")
                    .append(fmt(profile.recallAtK()))
                    .append(" | ")
                    .append(fmt(profile.mrr()))
                    .append(" | ")
                    .append(fmt(profile.citationPrecision()))
                    .append(" | ")
                    .append(fmt(profile.citationRecall()))
                    .append(" | ")
                    .append(fmt(profile.gateAbstentionRate()))
                    .append(" | ")
                    .append(fmt(profile.refusalCorrectness()))
                    .append(" | ")
                    .append(profile.latency().p50().toMillis())
                    .append(" | ")
                    .append(profile.latency().p95().toMillis())
                    .append(" | ")
                    .append(profile.totalPromptTokens())
                    .append(" | ")
                    .append(profile.totalCompletionTokens())
                    .append(" |\n");
        }
        md.append('\n');
        md.append(howToReadDeclining(report.config().runJudge()));

        if (report.config().runJudge()) {
            md.append("## LLM judge\n\n");
            if (recordedProfile) {
                md.append("Judge scores were computed but **are not shown here** — under the `recorded` "
                        + "profile a fixture-replay judge grading a fixture-replay answer proves nothing "
                        + "about answer quality. See `eval_result.metrics` for the raw (not meaningful) "
                        + "values if needed for mechanism debugging.\n\n");
            } else {
                md.append("See the methodology limitations below before reading these as absolute quality "
                        + "figures — they're for relative comparison within this report only.\n\n");
            }
        }

        md.append(limitations(recordedProfile));
        return md.toString();
    }

    /** Emitted on every report, because the previous single "Abstention acc." column was read as a
     * hallucination measurement and a 0.00 in it was read as total failure — when in fact the model
     * had declined correctly on every case and the deterministic gate had correctly stayed silent
     * (post-roadmap review issue #61). The two columns cannot be collapsed and cannot be read
     * without this. */
    private static String howToReadDeclining(boolean runJudge) {
        StringBuilder md = new StringBuilder();
        md.append("## How to read the two declining columns\n\n");
        md.append("Both cover the `UNANSWERABLE` cases only, and they measure **different mechanisms**:\n\n");
        md.append("- **Gate abstention** — how often the *deterministic* gate refused to generate, because the "
                + "best vector match was farther than the profile's `maxVectorDistance`. Structural and exact. "
                + "**A low value here is not a failure.** The gate is designed to catch \"this corpus does not "
                + "cover the topic\", never \"this specific fact is not stated\" "
                + "(docs/adr/0013-rag-abstention-threshold.md), so on a dataset whose unanswerable cases sit "
                + "*inside* the corpus it is expected to stay silent.\n");
        md.append("- **Refusal correctness** — whether the turn actually declined *correctly*, by whichever "
                + "mechanism, including the model declining in its own prose. Scored by the judge against the "
                + "refusal-shaped `expectedAnswer` the dataset provides for these cases.\n\n");
        if (!runJudge) {
            md.append("Refusal correctness reads `n/a` in this report because the judge was not run "
                    + "(`--judge`). That is **not measured**, not zero — and it is the column that would tell "
                    + "you whether the answers were right. Nothing else here does.\n\n");
        }
        return md.toString();
    }

    private static String fmt(RepeatedMetric metric) {
        // Locale.ROOT, not the platform default: a report committed from one machine must render
        // identically to one from CI — String.formatted() without a fixed locale would otherwise use
        // comma decimal separators on e.g. a Spanish-locale JVM, which real-world testing here (an
        // actual live eval.sh run) caught in the rendered Markdown table.
        return Double.isNaN(metric.mean())
                ? "n/a"
                : String.format(Locale.ROOT, "%.2f ± %.2f", metric.mean(), metric.spread());
    }

    private static String limitations(boolean recordedProfile) {
        StringBuilder md = new StringBuilder();
        md.append("## Methodology limitations\n\n");
        md.append("- **Judge scores are from a local model judging another local model** — a weak "
                + "instrument. Judges show self-preference and verbosity bias, and scores are not "
                + "comparable across judge models or prompt versions (docs/ai-evaluation.md §3).\n");
        if (recordedProfile) {
            md.append("- **Generated under the `recorded` profile** (fixture replay, no live model): proves "
                    + "the harness mechanics (retrieval, fusion, citation extraction, metric computation) "
                    + "run correctly, not real answer quality. Latency figures measure harness overhead, "
                    + "not real model latency. A real quality report requires running `./scripts/eval.sh` "
                    + "against a live LM Studio.\n");
        }
        md.append("- **Small, narrow corpus** (2 sources) — retrieval quality here does not generalise to a "
                + "large or heterogeneous corpus (corpus/ATTRIBUTION.md).\n");
        md.append("- No human evaluation; no comparison against other RAG systems; no adversarial or "
                + "red-team evaluation (docs/ai-evaluation.md §7).\n");
        md.append("- Deterministic metrics are a mean over the configured repetitions with the spread shown "
                + "(± after each figure) — a single-run number with no variance stated is an incomplete "
                + "measurement (docs/ai-evaluation.md §5).\n");
        return md.toString();
    }
}
