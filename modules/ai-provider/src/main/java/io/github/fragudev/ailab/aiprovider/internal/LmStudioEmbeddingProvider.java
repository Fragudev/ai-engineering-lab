package io.github.fragudev.ailab.aiprovider.internal;

import io.github.fragudev.ailab.aiprovider.Embedding;
import io.github.fragudev.ailab.aiprovider.EmbeddingProvider;
import io.github.fragudev.ailab.shared.ProviderUnavailableException;
import java.util.List;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;

final class LmStudioEmbeddingProvider implements EmbeddingProvider {

    private final EmbeddingModel embeddingModel;
    private final String modelName;

    LmStudioEmbeddingProvider(EmbeddingModel embeddingModel, String modelName) {
        this.embeddingModel = embeddingModel;
        this.modelName = modelName;
    }

    @Override
    public List<Embedding> embed(List<String> texts) {
        try {
            var response = embeddingModel.call(new EmbeddingRequest(texts, null));
            return response.getResults().stream()
                    .map(result -> new Embedding(result.getOutput()))
                    .toList();
        } catch (RuntimeException e) {
            // See LmStudioChatProvider: the SDK's connection-failure exception does not always
            // arrive here as the exact com.openai.errors.OpenAIIoException type.
            throw new ProviderUnavailableException("lmstudio", e);
        }
    }

    @Override
    public int dimensions() {
        return embeddingModel.dimensions();
    }

    @Override
    public String modelId() {
        return modelName;
    }
}
