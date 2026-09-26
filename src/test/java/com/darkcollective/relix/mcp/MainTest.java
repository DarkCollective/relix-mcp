package com.darkcollective.relix.mcp;

import com.darkcollective.relix.mcp.Main.Options;
import com.darkcollective.relix.mcp.http.LocalHttpServer;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class MainTest {

    private final HttpClient client = HttpClient.newBuilder().proxy(HttpClient.Builder.NO_PROXY).build();

    @Test
    void noArgumentsIsStdio() {
        assertThat(Options.parse(List.of()).http()).isFalse();
    }

    @Test
    void httpDefaultsToThisMachineOnPort8080() {
        Options options = Options.parse(List.of("--http"));

        assertThat(options.http()).isTrue();
        assertThat(options.host().isLoopbackAddress()).isTrue();
        assertThat(options.port()).isEqualTo(8080);
    }

    @Test
    void theHostAndPortCanBeNamed() throws IOException {
        Options options = Options.parse(List.of("--http", "--host", "0.0.0.0", "--port", "9000"));

        assertThat(options.host()).isEqualTo(InetAddress.getByName("0.0.0.0"));
        assertThat(options.port()).isEqualTo(9000);
    }

    @Test
    void aMistakeSaysWhat() {
        assertThatIllegalArgumentException().isThrownBy(() -> Options.parse(List.of("--htp")))
                .withMessageContaining("--htp");
        assertThatIllegalArgumentException().isThrownBy(() -> Options.parse(List.of("--http", "--port")))
                .withMessageContaining("needs a value");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> Options.parse(List.of("--http", "--port", "http")))
                .withMessageContaining("0 to 65535");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> Options.parse(List.of("--http", "--port", "70000")))
                .withMessageContaining("0 to 65535");
        assertThatIllegalArgumentException().isThrownBy(() -> Options.parse(List.of("--port", "1")))
                .withMessageContaining("only with --http");
    }

    @Test
    void theHttpServerAnswersAToolCallAtMcp() throws Exception {
        try (LocalHttpServer server = Main.serveHttp(
                new InetSocketAddress(InetAddress.getLoopbackAddress(), 0))) {
            assertThat(server.uri().toString()).matches("http://127\\.0\\.0\\.1:\\d+/mcp");

            HttpResponse<String> response = post(server.uri(), """
                    {"jsonrpc":"2.0","id":7,"method":"tools/call","params":{"name":"validate",
                     "arguments":{"script":"query { SELECT a > 1 (Nowhere) };"}}}""");

            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(response.headers().firstValue("Content-Type")).hasValueSatisfying(
                    type -> assertThat(type).startsWith("application/json"));
            assertThat(response.body()).contains("\"id\":7").contains("Nowhere");
        }
    }

    @Test
    void anotherPathIsNotFoundAndAnOversizedBodyIsRefused() throws Exception {
        try (LocalHttpServer server = Main.serveHttp(
                new InetSocketAddress(InetAddress.getLoopbackAddress(), 0))) {
            assertThat(post(server.uri().resolve("/mcp/other"), "{}").statusCode()).isEqualTo(404);
            assertThat(post(server.uri(), "x".repeat(2 * 1024 * 1024)).statusCode()).isEqualTo(413);
        }
    }

    private HttpResponse<String> post(URI uri, String body) throws Exception {
        return client.send(HttpRequest.newBuilder(uri)
                        .header("Accept", "application/json, text/event-stream")
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                HttpResponse.BodyHandlers.ofString());
    }
}
