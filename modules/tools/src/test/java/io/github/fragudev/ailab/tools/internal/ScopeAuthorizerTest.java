package io.github.fragudev.ailab.tools.internal;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.fragudev.ailab.tools.ToolDefinition;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ScopeAuthorizerTest {

    private static ToolDefinition definitionRequiring(String... scopes) {
        return new ToolDefinition("some-tool", "1", "desc", "{}", "{}", Set.of(scopes), false, Duration.ofSeconds(5));
    }

    private static ScopeAuthorizer authorizerWithGrantedScopes(String... granted) {
        return new ScopeAuthorizer(
                new ToolsProperties(true, List.of(granted), Duration.ofSeconds(5), Duration.ofSeconds(60), 3));
    }

    @Test
    void authorizedWhenAllRequiredScopesAreGranted() {
        ScopeAuthorizer authorizer = authorizerWithGrantedScopes("calculator:use", "knowledge-base:search");

        assertThat(authorizer.isAuthorized(definitionRequiring("calculator:use")))
                .isTrue();
        assertThat(authorizer.missingScopes(definitionRequiring("calculator:use")))
                .isEmpty();
    }

    @Test
    void deniedWhenARequiredScopeIsMissing() {
        ScopeAuthorizer authorizer = authorizerWithGrantedScopes("calculator:use");

        ToolDefinition definition = definitionRequiring("external-api:mock");

        assertThat(authorizer.isAuthorized(definition)).isFalse();
        assertThat(authorizer.missingScopes(definition)).containsExactly("external-api:mock");
    }

    @Test
    void toolRequiringNoScopesIsAlwaysAuthorized() {
        ScopeAuthorizer authorizer = authorizerWithGrantedScopes();

        assertThat(authorizer.isAuthorized(definitionRequiring())).isTrue();
    }
}
