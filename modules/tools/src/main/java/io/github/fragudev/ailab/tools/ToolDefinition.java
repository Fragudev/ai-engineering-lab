package io.github.fragudev.ailab.tools;

import java.time.Duration;
import java.util.Set;

/**
 * A registered tool's static shape — name, version, JSON Schemas, required scopes
 * (docs/architecture.md #4). {@code introducesRetrievedContent} marks a tool whose result injects
 * untrusted document content into the conversation (true only for the knowledge-base-search tool
 * today) — {@link ToolCallingChatService} latches a turn into requiring confirmation the moment such
 * a tool actually executes, not just when the turn started RAG-augmented (docs/threat-model.md T2).
 *
 * @param name unique registry key, e.g. {@code "calculator"}
 * @param inputSchemaJson a JSON Schema (draft 2020-12) document, as a string
 * @param outputSchemaJson a JSON Schema document describing {@code ToolResult.resultJson}'s shape;
 *     informational only — not validated against today
 */
public record ToolDefinition(
        String name,
        String version,
        String description,
        String inputSchemaJson,
        String outputSchemaJson,
        Set<String> requiredScopes,
        boolean introducesRetrievedContent,
        Duration timeout) {}
