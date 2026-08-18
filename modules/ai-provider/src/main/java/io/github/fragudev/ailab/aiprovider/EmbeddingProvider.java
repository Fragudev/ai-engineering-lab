package io.github.fragudev.ailab.aiprovider;

import java.util.List;

/** Project-owned embedding interface; see {@link ChatProvider} for the rationale. */
public interface EmbeddingProvider {

    List<Embedding> embed(List<String> texts);

    int dimensions();

    String modelId();
}
