package com.darkcollective.relix.mcp.aws;

import io.modelcontextprotocol.json.McpJsonMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * The AWS credentials a request is signed with.
 *
 * @param accessKeyId     the access key id
 * @param secretAccessKey the secret key
 * @param sessionToken    the session token of temporary credentials, or null
 * @param expiration      when they stop working, or null when they do not
 */
record Credentials(String accessKeyId, String secretAccessKey, String sessionToken,
                   Instant expiration) {

    /** How long before credentials expire that they are fetched again. */
    static final Duration EARLY = Duration.ofMinutes(5);

    Credentials {
        Objects.requireNonNull(accessKeyId, "accessKeyId");
        Objects.requireNonNull(secretAccessKey, "secretAccessKey");
    }

    /**
     * The function's credentials, from its environment.
     *
     * <p>Lambda usually puts them in {@code AWS_ACCESS_KEY_ID}, {@code AWS_SECRET_ACCESS_KEY}
     * and {@code AWS_SESSION_TOKEN}. A SnapStart function has none there: its credentials
     * come from a container endpoint, {@code AWS_CONTAINER_CREDENTIALS_FULL_URI}, asked with
     * {@code AWS_CONTAINER_AUTHORIZATION_TOKEN}, and they expire.
     *
     * @param env  the function's environment
     * @param json reads the endpoint's answer
     * @return the credentials
     */
    static Credentials of(Map<String, String> env, McpJsonMapper json) {
        String id = env.get("AWS_ACCESS_KEY_ID");
        String secret = env.get("AWS_SECRET_ACCESS_KEY");
        if (id != null && !id.isBlank() && secret != null && !secret.isBlank()) {
            String token = env.get("AWS_SESSION_TOKEN");
            return new Credentials(id, secret, token == null || token.isBlank() ? null : token, null);
        }
        String endpoint = env.get("AWS_CONTAINER_CREDENTIALS_FULL_URI");
        String authorization = env.get("AWS_CONTAINER_AUTHORIZATION_TOKEN");
        if (endpoint == null || authorization == null) {
            throw new IllegalStateException("no AWS credentials: neither AWS_ACCESS_KEY_ID nor "
                    + "AWS_CONTAINER_CREDENTIALS_FULL_URI is set");
        }
        HttpRequest request = HttpRequest.newBuilder(URI.create(endpoint))
                .header("Authorization", authorization)
                .timeout(Duration.ofSeconds(5))
                .GET().build();
        try {
            HttpResponse<String> response = HttpClient.newBuilder()
                    .proxy(HttpClient.Builder.NO_PROXY)
                    .connectTimeout(Duration.ofSeconds(2)).build()
                    .send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw new IllegalStateException("the container credentials endpoint answered "
                        + response.statusCode());
            }
            Map<?, ?> answer = json.readValue(response.body(), Map.class);
            if (answer.get("AccessKeyId") instanceof String key
                    && answer.get("SecretAccessKey") instanceof String secretKey) {
                return new Credentials(key, secretKey,
                        answer.get("Token") instanceof String token ? token : null,
                        answer.get("Expiration") instanceof String expires ? Instant.parse(expires) : null);
            }
            throw new IllegalStateException("the container credentials endpoint gave no credentials");
        } catch (IOException e) {
            throw new IllegalStateException("cannot read the container credentials endpoint: "
                    + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted reading AWS credentials", e);
        }
    }

    /**
     * Credentials fetched when first needed, kept until shortly before they expire, and
     * fetched again then or when {@link #forget()} says they were refused.
     *
     * <p>Fetched on first use and not before: under SnapStart the handler is built before the
     * snapshot, and credentials fetched then would be the snapshot's.
     */
    static final class Cache implements Supplier<Credentials> {

        private final Supplier<Credentials> source;
        private final Clock clock;
        private Credentials held;

        Cache(Supplier<Credentials> source, Clock clock) {
            this.source = Objects.requireNonNull(source, "source");
            this.clock = Objects.requireNonNull(clock, "clock");
        }

        @Override
        public synchronized Credentials get() {
            if (held == null || held.expiration() != null
                    && !clock.instant().plus(EARLY).isBefore(held.expiration())) {
                held = source.get();
            }
            return held;
        }

        /** Drops the credentials held, so the next request fetches them again. */
        synchronized void forget() {
            held = null;
        }
    }
}
