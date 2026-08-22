package io.github.fragudev.ailab.evaluation.internal;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
        assertThat(profile.get("abstentionAccuracy").get("mean").asDouble()).isEqualTo(1.0);
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

    private EvalReport reportWithOneProfile(
            String ragProfile,
            double recallMean,
            double recallSpread,
            double citationPrecisionMean,
            double abstentionMean) {
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
                LatencyStats.of(List.of(Duration.ofMillis(10))),
                5,
                5,
                List.of(List.of(caseResult)));

        EvalRunConfig config =
                new EvalRunConfig(Path.of("eval/dataset/test.yaml"), List.of(ragProfile), 1, false, null);
        return new EvalReport(dataset, config, List.of(profile), "recorded-fixture", Instant.now());
    }
}
