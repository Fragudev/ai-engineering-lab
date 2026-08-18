package io.github.fragudev.ailab.aiprovider;

/** Fixed at 1024 dimensions project-wide (bge-m3); see docs/adr/0003-persistence-and-vector-store.md. */
public record Embedding(float[] vector) {}
