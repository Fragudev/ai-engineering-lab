package io.github.fragudev.ailab.aiprovider.internal;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.fragudev.ailab.aiprovider.ChatChunk;
import io.github.fragudev.ailab.aiprovider.ChatMessage;
import io.github.fragudev.ailab.aiprovider.ChatProvider;
import io.github.fragudev.ailab.aiprovider.ChatRequest;
import io.github.fragudev.ailab.aiprovider.ChatResponse;
import io.github.fragudev.ailab.aiprovider.ChatRole;
import io.github.fragudev.ailab.aiprovider.ProviderCapabilities;
import io.github.fragudev.ailab.aiprovider.TokenUsage;
import io.github.fragudev.ailab.aiprovider.internal.FixtureSet.FixtureCase;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Replays a small set of captured fixtures instead of calling a live model. This is what CI uses
 * (docs/architecture.md #8), and what makes the application fully explorable without LM Studio
 * running. Not a general-purpose HTTP cassette system — a fixed, reviewable set of canned Q&A pairs
 * is all a portfolio-scale demo needs; see scripts/record-fixtures.sh.
 */
final class RecordedChatProvider implements ChatProvider {

    private static final String MODEL_NAME = "recorded-fixture";
    private static final Duration SIMULATED_TOKEN_DELAY = Duration.ofMillis(25);

    private final FixtureSet fixtures;

    RecordedChatProvider() {
        // A dedicated, private ObjectMapper — deliberately not the app's autoconfigured Jackson
        // bean, so this adapter never depends on which Jackson generation (2.x vs 3.x, both
        // present in Spring Boot 4.1's dependency management) the rest of the app happens to wire.
        this.fixtures = loadFixtures(new ObjectMapper());
    }

    @Override
    public ChatResponse complete(ChatRequest request) {
        FixtureCase fixture = matchFixture(request);
        return toChatResponse(fixture, Duration.ZERO);
    }

    @Override
    public Flux<ChatChunk> stream(ChatRequest request) {
        Instant start = Instant.now();
        FixtureCase fixture = matchFixture(request);
        String[] words = fixture.response().split(" ");

        return Flux.fromArray(words)
                .delayElements(SIMULATED_TOKEN_DELAY)
                .index()
                .map(indexed -> ChatChunk.delta(indexed.getT1() == 0 ? indexed.getT2() : " " + indexed.getT2()))
                .concatWith(Mono.defer(() ->
                        Mono.just(ChatChunk.last(toChatResponse(fixture, Duration.between(start, Instant.now()))))));
    }

    @Override
    public ProviderCapabilities capabilities() {
        return new ProviderCapabilities(false, false, 8192);
    }

    private FixtureCase matchFixture(ChatRequest request) {
        String lastUserMessage = request.messages().stream()
                .filter(message -> message.role() == ChatRole.USER)
                .map(ChatMessage::content)
                .reduce((first, second) -> second)
                .orElse("")
                .toLowerCase(Locale.ROOT);

        return fixtures.cases().stream()
                .filter(fixtureCase ->
                        lastUserMessage.contains(fixtureCase.matchContains().toLowerCase(Locale.ROOT)))
                .findFirst()
                .orElse(fixtures.defaultCase());
    }

    private static ChatResponse toChatResponse(FixtureCase fixture, Duration latency) {
        return new ChatResponse(
                fixture.response(),
                MODEL_NAME,
                new TokenUsage(fixture.promptTokens(), fixture.completionTokens()),
                latency,
                BigDecimal.ZERO);
    }

    private static FixtureSet loadFixtures(ObjectMapper objectMapper) {
        try (InputStream in = RecordedChatProvider.class.getResourceAsStream("/fixtures/chat/fixtures.json")) {
            if (in == null) {
                throw new IllegalStateException("Missing classpath resource fixtures/chat/fixtures.json");
            }
            return objectMapper.readValue(in, FixtureSet.class);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to load chat fixtures", e);
        }
    }
}
