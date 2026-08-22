package io.github.fragudev.ailab.tools;

import java.util.UUID;

/** The model's intent to call a tool, as detected/parsed by {@link ToolCallingChatService} — a
 * chat-stream event, not yet validated, authorized or executed. */
public record ToolCallRequest(UUID callId, String toolName, String argumentsJson) {}
