package com.darkcollective.relix.mcp.http;

import com.darkcollective.relix.mcp.HttpServers;
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.server.transport.ServerTransportSecurityValidator;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetAddress;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class StatelessHttpTransportTest {

    private static final McpJsonMapper JSON = McpJsonDefaults.getMapper();
    private static final Map<String, List<String>> CLIENT = Map.of(
            "Accept", List.of("application/json, text/event-stream"),
            "Content-Type", List.of("application/json"));

    private final StatelessHttpTransport transport =
            HttpServers.withTools(ServerTransportSecurityValidator.NOOP);

    @Test
    void initializeIsAnsweredInTheResponseBody() throws IOException {
        StatelessHttpTransport.Response response = post(request(1, "initialize", Map.of(
                "protocolVersion", "2025-06-18",
                "capabilities", Map.of(),
                "clientInfo", Map.of("name", "test", "version", "1"))));

        assertThat(response.status()).isEqualTo(200);
        assertThat(response.headers().get("Content-Type")).startsWith("application/json");
        Map<?, ?> result = (Map<?, ?>) read(response).get("result");
        assertThat(((Map<?, ?>) result.get("serverInfo")).get("name")).isEqualTo("relix");
        assertThat((String) result.get("instructions")).contains("validate");
    }

    @Test
    void everyToolIsListedAndCallable() throws IOException {
        Map<?, ?> listed = (Map<?, ?>) read(post(request(2, "tools/list", Map.of()))).get("result");
        assertThat((List<?>) listed.get("tools")).extracting(t -> String.valueOf(((Map<?, ?>) t).get("name")))
                .containsExactly("validate", "explain", "run", "learn");

        Map<?, ?> called = read(post(request(3, "tools/call", Map.of("name", "run",
                "arguments", Map.of("script", "query { PROJECT Round(2.5) -> r (UNIT) };")))));

        assertThat(called.get("id")).isEqualTo(3);
        Map<?, ?> result = (Map<?, ?>) called.get("result");
        assertThat(result.get("isError")).isNotEqualTo(true);
        assertThat(result.toString()).contains("| r |");
    }

    @Test
    void aNotificationIsAcceptedWithNoBody() throws IOException {
        StatelessHttpTransport.Response response = post(JSON.writeValueAsString(Map.of(
                "jsonrpc", "2.0", "method", "notifications/initialized")));

        assertThat(response.status()).isEqualTo(202);
        assertThat(response.body()).isNull();
    }

    @Test
    void getOpensNoStream() {
        StatelessHttpTransport.Response response = transport.handle("GET", CLIENT, null);

        assertThat(response.status()).isEqualTo(405);
        assertThat(response.headers()).containsEntry("Allow", "POST");
    }

    @Test
    void aClientThatWillNotReadJsonIsRefused() throws IOException {
        StatelessHttpTransport.Response response = transport.handle("POST",
                Map.of("Accept", List.of("text/event-stream")), request(1, "ping", Map.of()));

        assertThat(response.status()).isEqualTo(406);
        assertThat(transport.handle("POST", Map.of("accept", List.of("*/*")),
                request(1, "ping", Map.of())).status()).isEqualTo(200);
        assertThat(transport.handle("POST", Map.of(), request(1, "ping", Map.of())).status())
                .isEqualTo(200);
    }

    @Test
    void aBodyThatIsNotJsonIsRefused() throws IOException {
        StatelessHttpTransport.Response response = transport.handle("POST",
                Map.of("Content-Type", List.of("text/plain")), request(1, "ping", Map.of()));

        assertThat(response.status()).isEqualTo(415);
    }

    @Test
    void anUnreadableMessageIsAJsonRpcParseErrorWithANullId() throws IOException {
        StatelessHttpTransport.Response response = post("{not json");

        assertThat(response.status()).isEqualTo(400);
        Map<?, ?> error = read(response);
        assertThat(error.containsKey("id")).isTrue();
        assertThat(error.get("id")).isNull();
        assertThat(((Map<?, ?>) error.get("error")).get("code")).isEqualTo(-32700);
        assertThat(post("").status()).isEqualTo(400);
    }

    @Test
    void aResponseIsRefused() throws IOException {
        StatelessHttpTransport.Response response = post(JSON.writeValueAsString(Map.of(
                "jsonrpc", "2.0", "id", 1, "result", Map.of())));

        assertThat(response.status()).isEqualTo(400);
        assertThat(((Map<?, ?>) read(response).get("error")).get("code")).isEqualTo(-32600);
    }

    @Test
    void aBodyOverTheCapIsRefusedUnread() {
        StatelessHttpTransport.Response response =
                post("x".repeat(StatelessHttpTransport.MAX_BODY_BYTES + 1));

        assertThat(response.status()).isEqualTo(413);
    }

    @Test
    void aClosingTransportTakesNoMoreRequests() throws IOException {
        transport.closeGracefully().block();

        assertThat(post(request(1, "ping", Map.of())).status()).isEqualTo(503);
    }

    @Test
    void onALoopbackAddressOnlyThisMachinesNamesAreAccepted() throws IOException {
        StatelessHttpTransport local = HttpServers.withTools(
                LocalHttpServer.securityFor(InetAddress.getLoopbackAddress()));
        String ping = request(1, "ping", Map.of());

        assertThat(local.handle("POST", Map.of("Host", List.of("localhost:8080"),
                "Origin", List.of("http://localhost:3000")), ping).status()).isEqualTo(200);
        assertThat(local.handle("POST", Map.of("Host", List.of("127.0.0.1:8080")), ping).status())
                .isEqualTo(200);
        assertThat(local.handle("POST", Map.of("Host", List.of("localhost:8080"),
                "Origin", List.of("http://attacker.example")), ping).status()).isEqualTo(403);
        assertThat(local.handle("POST", Map.of("Host", List.of("attacker.example:8080")), ping)
                .status()).isEqualTo(421);
    }

    @Test
    void elsewhereNoNameIsChecked() throws IOException {
        StatelessHttpTransport open = HttpServers.withTools(
                LocalHttpServer.securityFor(InetAddress.getByName("0.0.0.0")));

        assertThat(open.handle("POST", Map.of("Host", List.of("relix.example"),
                "Origin", List.of("https://claude.ai")), request(1, "ping", Map.of())).status())
                .isEqualTo(200);
    }

    private StatelessHttpTransport.Response post(String body) {
        return transport.handle("POST", CLIENT, body);
    }

    private static String request(int id, String method, Map<String, ?> params) throws IOException {
        return JSON.writeValueAsString(Map.of("jsonrpc", "2.0", "id", id, "method", method,
                "params", params));
    }

    private static Map<?, ?> read(StatelessHttpTransport.Response response) throws IOException {
        return JSON.readValue(response.body(), Map.class);
    }
}
