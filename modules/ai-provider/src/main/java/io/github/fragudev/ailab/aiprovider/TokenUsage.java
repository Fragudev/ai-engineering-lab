package io.github.fragudev.ailab.aiprovider;

public record TokenUsage(int promptTokens, int completionTokens) {

    public int totalTokens() {
        return promptTokens + completionTokens;
    }

    public static TokenUsage zero() {
        return new TokenUsage(0, 0);
    }
}
