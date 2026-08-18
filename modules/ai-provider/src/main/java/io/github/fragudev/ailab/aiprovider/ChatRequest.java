package io.github.fragudev.ailab.aiprovider;

import java.util.List;

/** The full turn history to send to the model; the last element is the newest user message. */
public record ChatRequest(List<ChatMessage> messages) {}
