package io.github.fragudev.ailab;

import io.github.fragudev.ailab.evaluation.EvalRunConfig;
import io.github.fragudev.ailab.evaluation.EvalRunner;
import java.nio.file.Path;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;

/**
 * CLI entry point for {@code scripts/eval.sh} — active only under the {@code eval-cli} profile,
 * which also sets {@code spring.main.web-application-type=none}
 * ({@code application-eval-cli.yml}). {@code ApplicationRunner} alone doesn't stop the JVM once
 * it's done — non-daemon background threads (Hikari, etc.) keep the process alive, as seen during
 * Phase 2's own throwaway DDL-generation run — so this exits explicitly at the end.
 */
@Component
@Profile("eval-cli")
class EvalCliRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(EvalCliRunner.class);

    private final EvalRunner evalRunner;
    private final Environment environment;
    private final ConfigurableApplicationContext context;

    EvalCliRunner(EvalRunner evalRunner, Environment environment, ConfigurableApplicationContext context) {
        this.evalRunner = evalRunner;
        this.environment = environment;
        this.context = context;
    }

    @Override
    public void run(ApplicationArguments args) {
        System.exit(SpringApplication.exit(context, () -> runAndGetExitCode(args)));
    }

    private int runAndGetExitCode(ApplicationArguments args) {
        int exitCode = 0;
        try {
            List<String> profiles = List.of(
                    firstOrDefault(args, "profiles", "dense-only,hybrid").split(","));
            Path dataset = Path.of(firstOrDefault(args, "dataset", "eval/dataset/core.yaml"));
            Path reportsDir = Path.of(firstOrDefault(args, "reports-dir", "eval/reports"));
            int repetitions = Integer.parseInt(firstOrDefault(args, "repetitions", "3"));
            boolean runJudge = args.containsOption("judge");
            String hardware = args.containsOption("hardware")
                    ? args.getOptionValues("hardware").get(0)
                    : null;
            boolean recordedProfileActive = environment.acceptsProfiles(Profiles.of("recorded"));

            EvalRunConfig config = new EvalRunConfig(dataset, profiles, repetitions, runJudge, hardware);
            log.info(
                    "Running evaluation: profiles={} dataset={} repetitions={} judge={} recordedProfile={}",
                    profiles,
                    dataset,
                    repetitions,
                    runJudge,
                    recordedProfileActive);

            Path report = evalRunner.runAndWriteReport(config, reportsDir, recordedProfileActive);
            log.info("Report written to {}", report.toAbsolutePath());
        } catch (RuntimeException e) {
            log.error("Evaluation run failed", e);
            exitCode = 1;
        }
        return exitCode;
    }

    private static String firstOrDefault(ApplicationArguments args, String name, String fallback) {
        return args.containsOption(name) ? args.getOptionValues(name).get(0) : fallback;
    }
}
