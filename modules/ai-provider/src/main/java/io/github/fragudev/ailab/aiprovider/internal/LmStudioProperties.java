package io.github.fragudev.ailab.aiprovider.internal;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/** LM Studio runs on the host, not in Compose; see AGENTS.md, Environment constraints. */
@Validated
@ConfigurationProperties(prefix = "ai.provider.lmstudio")
record LmStudioProperties(
        @NotBlank String baseUrl,
        @NotBlank String chatModel,
        @NotBlank String embeddingModel,
        @NotNull Duration timeout) {}
