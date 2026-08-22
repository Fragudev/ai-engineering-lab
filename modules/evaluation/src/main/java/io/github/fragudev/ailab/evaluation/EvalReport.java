package io.github.fragudev.ailab.evaluation;

import java.time.Instant;
import java.util.List;

public record EvalReport(
        EvalDataset dataset,
        EvalRunConfig config,
        List<ProfileSummary> profiles,
        String chatModel,
        Instant generatedAt) {}
