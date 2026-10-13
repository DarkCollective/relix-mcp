package com.darkcollective.relix.mcp.http;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ApiKeyAuthenticatorTest {

    private final Authenticator authenticator = new ApiKeyAuthenticator("s3cret-key");

    @Test
    void theKeyLetsARequestIn() {
        assertThat(authenticator.refusal(Map.of("X-Api-Key", List.of("s3cret-key")))).isEmpty();
        assertThat(authenticator.refusal(Map.of("x-api-key", List.of("s3cret-key")))).isEmpty();
    }

    @Test
    void anythingElseIsRefusedWith401() {
        for (Map<String, List<String>> headers : List.of(
                Map.<String, List<String>>of(),
                Map.of("X-Api-Key", List.of("s3cret-kez")),
                Map.of("X-Api-Key", List.of("s3cret-key-and-more")),
                Map.of("X-Api-Key", List.of("")),
                Map.of("X-Api-Key", List.of("s3cret-key", "s3cret-key")),
                Map.of("Authorization", List.of("Bearer s3cret-key")))) {
            assertThat(authenticator.refusal(headers)).as(headers.toString())
                    .hasValueSatisfying(r -> {
                        assertThat(r.status()).isEqualTo(401);
                        assertThat(r.body()).contains("X-Api-Key").doesNotContain("s3cret");
                    });
        }
    }

    @Test
    void aBlankKeyIsNoKey() {
        assertThatThrownBy(() -> new ApiKeyAuthenticator(" "))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void allowAllRefusesNoOne() {
        assertThat(Authenticator.allowAll().refusal(Map.of())).isEmpty();
    }
}
