package io.github.fragudev.ailab;

import jakarta.validation.constraints.NotBlank;

record RetrievalSearchRequest(
        @NotBlank String query, @NotBlank String ragProfile) {}
