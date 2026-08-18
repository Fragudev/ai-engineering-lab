package io.github.fragudev.ailab.aiprovider.internal;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** LM Studio runs on the host, not in Compose; see AGENTS.md, Environment constraints. */
@ConfigurationProperties(prefix = "ai.provider.lmstudio")
record LmStudioProperties(String baseUrl, String chatModel, String embeddingModel, Duration timeout) {}
