package io.github.fragudev.ailab;

import jakarta.validation.constraints.NotBlank;
import org.jspecify.annotations.Nullable;

record SendMessageRequest(
        @NotBlank String content, @Nullable String ragProfile) {}
