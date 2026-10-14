package com.darkcollective.relix.mcp.aws;

import com.sun.net.httpserver.HttpServer;
import io.modelcontextprotocol.json.McpJsonDefaults;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Against a stand-in for the extension, answering as its documentation says it does. */
class ParametersAndSecretsTest {

    private final List<String> asked = new CopyOnWriteArrayList<>();
    private HttpServer extension;
    private ParametersAndSecrets store;

    @BeforeEach
    void start() throws IOException {
        extension = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        extension.createContext("/", exchange -> {
            String query = exchange.getRequestURI().getRawQuery();
            asked.add(exchange.getRequestURI().getPath() + "?" + query + " token="
                    + exchange.getRequestHeaders().getFirst("X-Aws-Parameters-Secrets-Token"));
            String body = switch (exchange.getRequestURI().getPath()) {
                case "/systemsmanager/parameters/get" ->
                        "{\"Parameter\":{\"Name\":\"/relix/key\",\"Type\":\"SecureString\",\"Value\":\"from-ssm\"}}";
                case "/secretsmanager/get" -> "{\"Name\":\"relix\",\"SecretString\":\"from-secrets\\n\"}";
                case "/2021-04-23/credentials" -> "container-auth".equals(
                        exchange.getRequestHeaders().getFirst("Authorization"))
                        ? "{\"AccessKeyId\":\"AKID\",\"SecretAccessKey\":\"secret\","
                        + "\"Token\":\"from-container\",\"Expiration\":\"2026-10-14T12:00:00Z\"}"
                        : null;
                default -> null;
            };
            byte[] bytes = (body == null ? "not found" : body).getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(body == null ? 404 : 200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        extension.start();
        store = new ParametersAndSecrets(URI.create("http://127.0.0.1:"
                + extension.getAddress().getPort()), "session-token", McpJsonDefaults.getMapper());
    }

    @AfterEach
    void stop() {
        extension.stop(0);
    }

    @Test
    void aParameterIsReadDecrypted() {
        assertThat(LambdaHandler.apiKey(Map.of(LambdaHandler.KEY_PARAMETER, "/relix/key"),
                () -> store)).isEqualTo("from-ssm");
        assertThat(asked).containsExactly("/systemsmanager/parameters/get?withDecryption=true"
                + "&name=%2Frelix%2Fkey token=session-token");
    }

    @Test
    void aSecretIsReadAndTrimmed() {
        assertThat(LambdaHandler.apiKey(Map.of(LambdaHandler.KEY_SECRET, "relix"), () -> store))
                .isEqualTo("from-secrets");
    }

    @Test
    void theSessionTokenIsTheEnvironmentsWhenLambdaSetsOne() {
        assertThat(ParametersAndSecrets.sessionToken(Map.of("AWS_SESSION_TOKEN", "from-env",
                "AWS_CONTAINER_CREDENTIALS_FULL_URI", credentials(),
                "AWS_CONTAINER_AUTHORIZATION_TOKEN", "container-auth"),
                McpJsonDefaults.getMapper())).isEqualTo("from-env");
        assertThat(asked).isEmpty();
    }

    /** SnapStart sets no AWS_SESSION_TOKEN: the credentials come from a container endpoint. */
    @Test
    void underSnapStartTheSessionTokenComesFromTheContainerEndpoint() {
        assertThat(ParametersAndSecrets.sessionToken(Map.of(
                "AWS_CONTAINER_CREDENTIALS_FULL_URI", credentials(),
                "AWS_CONTAINER_AUTHORIZATION_TOKEN", "container-auth"),
                McpJsonDefaults.getMapper())).isEqualTo("from-container");
    }

    @Test
    void theContainerEndpointMustBeAskedWithItsToken() {
        assertThatThrownBy(() -> ParametersAndSecrets.sessionToken(Map.of(
                "AWS_CONTAINER_CREDENTIALS_FULL_URI", credentials(),
                "AWS_CONTAINER_AUTHORIZATION_TOKEN", "wrong"), McpJsonDefaults.getMapper()))
                .hasMessageContaining("container credentials endpoint answered 404");
    }

    private String credentials() {
        return "http://127.0.0.1:" + extension.getAddress().getPort() + "/2021-04-23/credentials";
    }

    @Test
    void outsideLambdaThereIsNoExtensionToAsk() {
        assertThatThrownBy(() -> ParametersAndSecrets.of(Map.of(), McpJsonDefaults.getMapper()))
                .hasMessageContaining("AWS_SESSION_TOKEN");
    }
}
