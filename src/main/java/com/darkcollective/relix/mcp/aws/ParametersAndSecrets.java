package com.darkcollective.relix.mcp.aws;

import io.modelcontextprotocol.json.McpJsonMapper;

import java.io.IOException;
import java.net.ConnectException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;

/**
 * Reads a value from Systems Manager Parameter Store or Secrets Manager through the AWS
 * Parameters and Secrets Lambda extension: an HTTP endpoint on the function's own host,
 * authorised by the function's session token.
 *
 * <p>Read through the extension rather than the AWS SDK, which would be most of the jar for
 * one call at start-up, and would bring providers of its own for {@code fatJarTest} to
 * cover. The extension is a layer the function is deployed with.
 */
final class ParametersAndSecrets {

    /** The port the extension listens on unless its own variable says another. */
    static final int DEFAULT_PORT = 2773;

    private static final int ATTEMPTS = 5;

    private final URI base;
    private final String token;
    private final McpJsonMapper json;
    private final HttpClient client = HttpClient.newBuilder()
            .proxy(HttpClient.Builder.NO_PROXY)
            .connectTimeout(Duration.ofSeconds(2))
            .build();

    /**
     * A reader of the extension at {@code base}.
     *
     * @param base  the extension's endpoint, such as {@code http://localhost:2773}
     * @param token the function's session token, which the extension requires of a caller
     * @param json  reads the extension's answers
     */
    ParametersAndSecrets(URI base, String token, McpJsonMapper json) {
        this.base = Objects.requireNonNull(base, "base");
        this.token = Objects.requireNonNull(token, "token");
        this.json = Objects.requireNonNull(json, "json");
    }

    /**
     * A reader of the extension a function was deployed with, from the function's
     * environment.
     *
     * @param env  the function's environment variables
     * @param json reads the extension's answers
     * @return the reader
     */
    static ParametersAndSecrets of(Map<String, String> env, McpJsonMapper json) {
        String token = env.get("AWS_SESSION_TOKEN");
        if (token == null) {
            throw new IllegalStateException("AWS_SESSION_TOKEN is not set: the extension "
                    + "is reached only from inside a Lambda function");
        }
        String port = env.getOrDefault("PARAMETERS_SECRETS_EXTENSION_HTTP_PORT",
                String.valueOf(DEFAULT_PORT));
        return new ParametersAndSecrets(URI.create("http://localhost:" + port), token, json);
    }

    /**
     * {@return the decrypted value of a Parameter Store parameter}
     *
     * @param name the parameter's name or ARN
     */
    String parameter(String name) {
        Map<?, ?> answer = get("/systemsmanager/parameters/get?withDecryption=true&name="
                + encode(name));
        if (answer.get("Parameter") instanceof Map<?, ?> parameter
                && parameter.get("Value") instanceof String value) {
            return value;
        }
        throw new IllegalStateException("parameter '" + name + "' has no value");
    }

    /**
     * {@return the text of a Secrets Manager secret}
     *
     * @param id the secret's name or ARN
     */
    String secret(String id) {
        if (get("/secretsmanager/get?secretId=" + encode(id)).get("SecretString")
                instanceof String value) {
            return value;
        }
        throw new IllegalStateException("secret '" + id + "' has no text");
    }

    private Map<?, ?> get(String path) {
        HttpRequest request = HttpRequest.newBuilder(base.resolve(path))
                .header("X-Aws-Parameters-Secrets-Token", token)
                .timeout(Duration.ofSeconds(5))
                .GET().build();
        for (int attempt = 1; ; attempt++) {
            try {
                HttpResponse<String> response = client.send(request,
                        HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() != 200) {
                    throw new IllegalStateException("the Parameters and Secrets extension "
                            + "answered " + response.statusCode() + ": " + response.body());
                }
                return json.readValue(response.body(), Map.class);
            } catch (ConnectException e) {
                // The extension starts beside the function and may not be listening yet.
                if (attempt == ATTEMPTS) {
                    throw new IllegalStateException("the Parameters and Secrets extension is "
                            + "not listening at " + base + "; deploy the function with its layer", e);
                }
                pause(attempt);
            } catch (IOException e) {
                throw new IllegalStateException("cannot read from the Parameters and Secrets "
                        + "extension: " + e.getMessage(), e);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("interrupted reading the API key", e);
            }
        }
    }

    private static void pause(int attempt) {
        try {
            Thread.sleep(100L * attempt);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted reading the API key", e);
        }
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
