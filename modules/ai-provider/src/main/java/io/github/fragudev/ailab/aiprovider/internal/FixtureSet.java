package io.github.fragudev.ailab.aiprovider.internal;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/** Deserialized shape of a chat fixture file under {@code fixtures/chat/}. */
record FixtureSet(@JsonProperty("default") FixtureCase defaultCase, List<FixtureCase> cases) {

    record FixtureCase(String matchContains, String response, int promptTokens, int completionTokens) {}
}
