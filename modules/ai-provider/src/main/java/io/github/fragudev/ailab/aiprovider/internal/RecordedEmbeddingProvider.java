package io.github.fragudev.ailab.aiprovider.internal;

import io.github.fragudev.ailab.aiprovider.Embedding;
import io.github.fragudev.ailab.aiprovider.EmbeddingProvider;
import java.util.List;
import java.util.Random;

/**
 * Hash-seeded, deterministic vectors — no network call, no live model, same input always produces
 * the same output. Matches the project-wide 1024 dimensions (bge-m3) so it's a drop-in stand-in for
 * `lmstudio` wherever real semantic quality doesn't matter, e.g. tests and CI.
 */
final class RecordedEmbeddingProvider implements EmbeddingProvider {

    private static final int DIMENSIONS = 1024;
    private static final String MODEL_NAME = "recorded-fixture-embedding";

    @Override
    public List<Embedding> embed(List<String> texts) {
        return texts.stream().map(RecordedEmbeddingProvider::embedOne).toList();
    }

    @Override
    public int dimensions() {
        return DIMENSIONS;
    }

    @Override
    public String modelId() {
        return MODEL_NAME;
    }

    private static Embedding embedOne(String text) {
        Random random = new Random(text.hashCode());
        float[] vector = new float[DIMENSIONS];
        for (int i = 0; i < DIMENSIONS; i++) {
            vector[i] = random.nextFloat() * 2f - 1f;
        }
        return new Embedding(vector);
    }
}
