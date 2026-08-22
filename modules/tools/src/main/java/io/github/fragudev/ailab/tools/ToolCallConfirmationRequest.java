package io.github.fragudev.ailab.tools;

import java.time.Duration;
import java.util.UUID;

/** Emitted on the chat stream when a tool call is gated on user approval (docs/threat-model.md T2).
 * Resolved by {@code POST /api/v1/tool-calls/{callId}:confirm} within {@code confirmationTimeout},
 * or the call resolves to {@link ToolCallOutcome#TIMEOUT} on its own. */
public record ToolCallConfirmationRequest(
        UUID callId, String toolName, String argumentsJson, Duration confirmationTimeout) {}
