package io.github.fragudev.ailab;

import jakarta.validation.constraints.NotBlank;

record SendMessageRequest(@NotBlank String content) {}
