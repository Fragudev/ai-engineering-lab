package io.github.fragudev.ailab.evaluation;

import java.nio.file.Path;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * @param datasetPath an {@code eval/dataset/*.yaml} file
 * @param ragProfiles profile names to compare, e.g. {@code ["dense-only", "hybrid"]}
 * @param repetitions how many times to run the whole dataset per profile, for the mean+spread
 *     docs/ai-evaluation.md §5 asks for — local models aren't fully deterministic even at
 *     temperature zero
 * @param runJudge whether to also run the secondary LLM-judge metrics (correctness, faithfulness)
 * @param hardware free-text hardware description for the report; {@code null} when not measured
 *     rather than guessed (AGENTS.md rule 2)
 */
public record EvalRunConfig(
        Path datasetPath,
        List<String> ragProfiles,
        int repetitions,
        boolean runJudge,
        @Nullable String hardware) {}
