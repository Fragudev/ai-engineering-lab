package io.github.fragudev.ailab.evaluation;

public record CaseResult(EvalCase evalCase, String answer, String modelUsed, CaseMetrics metrics) {}
