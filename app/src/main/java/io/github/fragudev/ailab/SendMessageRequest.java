package io.github.fragudev.ailab;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.jspecify.annotations.Nullable;

record SendMessageRequest(
        // 8,000 chars (~2,000 tokens at a rough 4-chars/token estimate) — a reasoned bound, not
        // measured against a dataset (AGENTS.md rule 2, docs/threat-model.md T5, post-roadmap
        // review S3), sized to leave real headroom inside the 8,192-token context window
        // ProviderCapabilities reports once history, retrieved context and tool schemas share it.
        @NotBlank @Size(max = 8000) String content,
        @Nullable String ragProfile) {}
