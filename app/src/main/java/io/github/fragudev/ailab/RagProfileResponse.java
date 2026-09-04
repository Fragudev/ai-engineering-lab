package io.github.fragudev.ailab;

import io.github.fragudev.ailab.knowledge.RerankStrategy;
import io.github.fragudev.ailab.rag.RagProfile;

record RagProfileResponse(
        String name,
        int topK,
        int candidatesPerRetriever,
        boolean lexicalEnabled,
        RerankStrategy rerankStrategy,
        double mmrLambda,
        int contextTokenBudget,
        double maxVectorDistance) {

    static RagProfileResponse from(RagProfile profile) {
        return new RagProfileResponse(
                profile.name(),
                profile.topK(),
                profile.candidatesPerRetriever(),
                profile.lexicalEnabled(),
                profile.rerankStrategy(),
                profile.mmrLambda(),
                profile.contextTokenBudget(),
                profile.maxVectorDistance());
    }
}
