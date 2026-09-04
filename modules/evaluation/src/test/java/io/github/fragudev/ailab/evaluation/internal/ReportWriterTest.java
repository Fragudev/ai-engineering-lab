package io.github.fragudev.ailab.evaluation.internal;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.fragudev.ailab.evaluation.CaseCoverage;
import io.github.fragudev.ailab.evaluation.CaseMetrics;
import io.github.fragudev.ailab.evaluation.CaseResult;
import io.github.fragudev.ailab.evaluation.EvalCase;
import io.github.fragudev.ailab.evaluation.EvalCaseCategory;
import io.github.fragudev.ailab.evaluation.EvalDataset;
import io.github.fragudev.ailab.evaluation.EvalReport;
import io.github.fragudev.ailab.evaluation.EvalRunConfig;
import io.github.fragudev.ailab.evaluation.ProfileSummary;
import io.github.fragudev.ailab.shared.EvalCaseId;
import io.github.fragudev.ailab.shared.EvalDatasetId;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@code scripts/check-eval-regression.sh} (.github/workflows/nightly-eval.yml) parses the JSON
 * sidecar this class writes by exact field path — these tests pin that contract, in particular that
 * an undefined ({@link Double#NaN}) mean is written as JSON {@code null}, never the non-standard
 * bare {@code NaN} token a naive Jackson write would otherwise produce.
 */
class ReportWriterTest {

    private final ReportWriter reportWriter = new ReportWriter();
    private final ObjectMapper json = new ObjectMapper();

    @TempDir
    private Path tempDir;

    @Test
    void writesMarkdownAndJsonSidecarWithMatchingAggregateNumbers() throws IOException {
        EvalReport report = reportWithOneProfile("dense-only", 0.8, 0.05, 0.9, 1.0);

        Path mdPath = reportWriter.write(report, tempDir, true);
        Path jsonPath = Path.of(mdPath.toString().replace(".md", ".json"));

        assertThat(mdPath).exists();
        assertThat(jsonPath).exists();

        JsonNode root = json.readTree(Files.readString(jsonPath));
        JsonNode profile = root.get("profiles").get(0);
        assertThat(profile.get("ragProfile").asText()).isEqualTo("dense-only");
        assertThat(profile.get("recallAtK").get("mean").asDouble()).isEqualTo(0.8);
        assertThat(profile.get("recallAtK").get("spread").asDouble()).isEqualTo(0.05);
        assertThat(profile.get("citationPrecision").get("mean").asDouble()).isEqualTo(0.9);
        assertThat(profile.get("gateAbstentionRate").get("mean").asDouble()).isEqualTo(1.0);
        // Post-roadmap review issue #61: the two declining mechanisms are reported separately, and
        // an unmeasured judge is null ("not measured"), never 0.0 ("declined incorrectly").
        assertThat(profile.get("refusalCorrectness")).isNotNull();
        assertThat(profile.get("refusalCorrectness").get("mean").isNull()).isTrue();
        assertThat(profile.has("abstentionAccuracy")).isFalse();
    }

    /** The rendered report must carry the reading guide, because the single collapsed column it
     * replaces was misread as a hallucination measurement in a real run (issue #61). */
    @Test
    void markdownExplainsHowToReadTheTwoDecliningColumns() throws IOException {
        EvalReport report = reportWithOneProfile("dense-only", 0.8, 0.0, 0.9, 0.0);

        String markdown = Files.readString(reportWriter.write(report, tempDir, false));

        assertThat(markdown).contains("Gate abstention").contains("Refusal correctness");
        assertThat(markdown).contains("A low value here is not a failure");
        // The judge did not run in this fixture, so the report must say the column is unmeasured
        // rather than leaving a bare "n/a" for a reader to interpret as a score of zero.
        assertThat(markdown).contains("**not measured**, not zero");
    }

    @Test
    void undefinedMeanIsWrittenAsJsonNullNotBareNanToken() throws IOException {
        EvalReport report = reportWithOneProfile("dense-only", Double.NaN, 0.0, 0.9, 1.0);

        Path mdPath = reportWriter.write(report, tempDir, true);
        Path jsonPath = Path.of(mdPath.toString().replace(".md", ".json"));
        String raw = Files.readString(jsonPath);

        assertThat(raw).doesNotContain("NaN");
        JsonNode profile = json.readTree(raw).get("profiles").get(0);
        assertThat(profile.get("recallAtK").get("mean").isNull()).isTrue();
    }

    @Test
    void jsonSidecarCarriesCaseCoverage() throws IOException {
        EvalReport report = reportWithOneProfile("dense-only", 0.8, 0.0, 0.9, 1.0, new CaseCoverage(84, 71));

        Path mdPath = reportWriter.write(report, tempDir, true);
        Path jsonPath = Path.of(mdPath.toString().replace(".md", ".json"));

        JsonNode coverage =
                json.readTree(Files.readString(jsonPath)).get("profiles").get(0).get("coverage");
        assertThat(coverage.get("attempted").asInt()).isEqualTo(84);
        assertThat(coverage.get("completed").asInt()).isEqualTo(71);
        assertThat(coverage.get("skipped").asInt()).isEqualTo(13);
    }

    @Test
    void markdownConfirmsCoverageWhenEveryCaseCompleted() throws IOException {
        EvalReport report = reportWithOneProfile("dense-only", 0.8, 0.0, 0.9, 1.0, new CaseCoverage(28, 28));

        String markdown = Files.readString(reportWriter.write(report, tempDir, true));

        assertThat(markdown).contains("## Case coverage");
        assertThat(markdown).contains("Every profile completed every attempted case run: **28 of 28**");
        assertThat(markdown).doesNotContain("Warning — incomplete coverage");
        // The comparison table gets a Cases column showing the ratio, unmarked when whole.
        assertThat(markdown).contains("| dense-only | 28/28 |");
    }

    @Test
    void markdownWarnsProminentlyWhenAProfileSkippedCases() throws IOException {
        EvalReport report =
                reportWithOneProfile("hybrid-rerank", 0.71, 0.0, 0.65, Double.NaN, new CaseCoverage(84, 14));

        String markdown = Files.readString(reportWriter.write(report, tempDir, false));

        assertThat(markdown).contains("**Warning — incomplete coverage.** 14 of 84");
        assertThat(markdown).contains("subsample");
        assertThat(markdown).contains("`hybrid-rerank` — 14 of 84 completed, 70 skipped (17% coverage)");
        // The row itself is flagged, not just the section above it.
        assertThat(markdown).contains("| hybrid-rerank | ⚠ 14/84 |");
    }

    private EvalReport reportWithOneProfile(
            String ragProfile,
            double recallMean,
            double recallSpread,
            double citationPrecisionMean,
            double abstentionMean) {
        return reportWithOneProfile(
                ragProfile, recallMean, recallSpread, citationPrecisionMean, abstentionMean, new CaseCoverage(1, 1));
    }

    private EvalReport reportWithOneProfile(
            String ragProfile,
            double recallMean,
            double recallSpread,
            double citationPrecisionMean,
            double abstentionMean,
            CaseCoverage coverage) {
        EvalDataset dataset = new EvalDataset(EvalDatasetId.generate(), "test-dataset", "1");
        EvalCase evalCase = new EvalCase(
                EvalCaseId.generate(),
                dataset.id(),
                "case-1",
                "question?",
                "expected answer",
                new String[0],
                new String[0],
                EvalCaseCategory.FACTUAL_SINGLE_HOP);
        CaseMetrics metrics = new CaseMetrics(
                recallMean, 1.0, citationPrecisionMean, 1.0, null, Duration.ofMillis(10), 5, 5, null, null);
        CaseResult caseResult = new CaseResult(evalCase, "answer", "recorded-fixture", metrics);

        ProfileSummary profile = new ProfileSummary(
                ragProfile,
                new RepeatedMetric(recallMean, recallSpread),
                new RepeatedMetric(1.0, 0.0),
                new RepeatedMetric(citationPrecisionMean, 0.0),
                new RepeatedMetric(1.0, 0.0),
                new RepeatedMetric(abstentionMean, 0.0),
                // Refusal correctness: NaN, i.e. the judge did not run — this fixture's config sets
                // runJudge=false, so "not measured" is the honest value and renders as "n/a".
                new RepeatedMetric(Double.NaN, 0.0),
                LatencyStats.of(List.of(Duration.ofMillis(10))),
                5,
                5,
                coverage,
                List.of(List.of(caseResult)));

        EvalRunConfig config =
                new EvalRunConfig(Path.of("eval/dataset/test.yaml"), List.of(ragProfile), 1, false, null);
        return new EvalReport(dataset, config, List.of(profile), "recorded-fixture", Instant.now());
    }
}
