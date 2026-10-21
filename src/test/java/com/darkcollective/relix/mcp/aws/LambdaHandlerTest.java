package com.darkcollective.relix.mcp.aws;

import com.amazonaws.services.lambda.runtime.ClientContext;
import com.amazonaws.services.lambda.runtime.CognitoIdentity;
import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.LambdaLogger;
import com.darkcollective.relix.embed.Sandbox;
import com.darkcollective.relix.mcp.Deadline;
import com.darkcollective.relix.mcp.SlowScript;
import com.darkcollective.relix.mcp.data.DataKeys;
import com.darkcollective.relix.mcp.data.InMemoryDataStore;
import com.darkcollective.relix.mcp.http.ApiKeyAuthenticator;
import com.darkcollective.relix.mcp.http.StatelessHttpTransport;
import com.darkcollective.relix.mcp.run.ScriptRunner;
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.json.McpJsonMapper;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LambdaHandlerTest {

    private static final McpJsonMapper JSON = McpJsonDefaults.getMapper();
    private static final String KEY = "test-key-0123456789";

    /** {@code relix_run} with no limit of the sandbox's own to reach before the deadline. */
    private final LambdaHandler handler = new LambdaHandler(new ApiKeyAuthenticator(KEY),
            new ScriptRunner(Sandbox.builder().build()));

    @Test
    void aFunctionUrlEventIsAnsweredLikeAnHttpRequest() throws IOException {
        Map<?, ?> answer = invoke(event(KEY, call(1, "relix_run",
                "query { PROJECT Round(2.5) -> r (UNIT) };")), Duration.ofSeconds(30));

        assertThat(answer.get("statusCode")).isEqualTo(200);
        assertThat(answer.get("isBase64Encoded")).isEqualTo(false);
        assertThat(((Map<?, ?>) answer.get("headers")).get("Content-Type").toString())
                .startsWith("application/json");
        Map<?, ?> result = (Map<?, ?>) JSON.readValue((String) answer.get("body"), Map.class)
                .get("result");
        assertThat(result.get("isError")).isNotEqualTo(true);
        assertThat(result.toString()).contains("| r NUMBER |");
    }

    @Test
    void theWarmUpIsAnsweredWithoutError() {
        handler.warm();
    }

    /** A tool the warm-up leaves out would load what it needs on a user's first call. */
    @Test
    void theWarmUpCallsEveryTool() throws IOException {
        Map<?, ?> answer = invoke(event(KEY,
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\"}"), Duration.ofSeconds(30));
        List<?> tools = (List<?>) ((Map<?, ?>) JSON.readValue((String) answer.get("body"), Map.class)
                .get("result")).get("tools");

        assertThat(tools).isNotEmpty().allSatisfy(tool -> {
            String name = (String) ((Map<?, ?>) tool).get("name");
            assertThat(LambdaHandler.WARM_UP).as(name).anySatisfy(message ->
                    assertThat(message).contains("\"name\":\"" + name + "\""));
        });
    }

    @Test
    void withoutTheKeyNothingReachesTheServer() throws IOException {
        for (String key : new String[] {null, "wrong", KEY + " "}) {
            Map<?, ?> answer = invoke(event(key, "{not even json"), Duration.ofSeconds(30));

            assertThat(answer.get("statusCode")).as(String.valueOf(key)).isEqualTo(401);
            assertThat((String) answer.get("body")).contains("X-Api-Key");
        }
    }

    @Test
    void aBase64BodyIsDecoded() throws IOException {
        Map<String, Object> event = event(KEY, Base64.getEncoder().encodeToString(
                call(1, "relix_learn", "").getBytes(StandardCharsets.UTF_8)));
        event.put("isBase64Encoded", true);

        assertThat(invoke(event, Duration.ofSeconds(30)).get("statusCode")).isEqualTo(200);
    }

    @Test
    void onlyTheMcpPathIsServed() throws IOException {
        Map<String, Object> event = event(KEY, call(1, "relix_learn", ""));
        event.put("rawPath", "/");

        assertThat(invoke(event, Duration.ofSeconds(30)).get("statusCode")).isEqualTo(404);
    }

    @Test
    void behindACdnThePathIsTheCdns() throws IOException {
        LambdaHandler behindCdn = new LambdaHandler(new ApiKeyAuthenticator(KEY),
                new ScriptRunner(), "/mcp/private");
        Map<String, Object> event = event(KEY, call(1, "relix_learn", ""));
        event.put("rawPath", "/mcp/private");

        assertThat(behindCdn.respond(event, Deadline.after(Duration.ofSeconds(30))).status())
                .isEqualTo(200);
        event.put("rawPath", "/mcp");
        assertThat(behindCdn.respond(event, Deadline.after(Duration.ofSeconds(30))).status())
                .isEqualTo(404);
        assertThat(LambdaHandler.path(Map.of())).isEqualTo(LambdaHandler.PATH);
        assertThat(LambdaHandler.path(Map.of(LambdaHandler.PATH_VARIABLE, "/mcp/private")))
                .isEqualTo("/mcp/private");
        assertThatThrownBy(() -> LambdaHandler.path(Map.of(LambdaHandler.PATH_VARIABLE, "mcp")))
                .hasMessageContaining(LambdaHandler.PATH_VARIABLE);
    }

    @Test
    void aNotificationHasNoBody() throws IOException {
        Map<?, ?> answer = invoke(event(KEY,
                "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}"),
                Duration.ofSeconds(30));

        assertThat(answer.get("statusCode")).isEqualTo(202);
        assertThat(answer.containsKey("body")).isFalse();
    }

    @Test
    void aScriptPastTheDeadlineIsAnsweredBeforeThePlatformStopsIt() throws IOException {
        Duration platform = Deadline.MARGIN.plusMillis(500);
        long started = System.nanoTime();
        Map<?, ?> answer = invoke(event(KEY, call(7, "relix_run", SlowScript.TEXT)), platform);
        Duration took = Duration.ofNanos(System.nanoTime() - started);

        assertThat(took).isLessThan(platform);
        assertThat(answer.get("statusCode")).isEqualTo(200);
        Map<?, ?> response = JSON.readValue((String) answer.get("body"), Map.class);
        assertThat(response.get("id")).isEqualTo(7);
        Map<?, ?> result = (Map<?, ?>) response.get("result");
        assertThat(result.get("isError")).isEqualTo(true);
        assertThat(result.toString()).contains("Stopped at the 1-second limit")
                .contains("| 10 |").contains("Stopped before query 2 finished");
    }

    @Test
    void withoutOnePlaceForTheKeyTheFunctionDoesNotStart() {
        Supplier<ParametersAndSecrets> unasked = () -> {
            throw new AssertionError("the extension is not asked");
        };
        assertThatThrownBy(() -> LambdaHandler.apiKey(Map.of(), unasked))
                .hasMessageContaining(LambdaHandler.KEY_PARAMETER);
        assertThatThrownBy(() -> LambdaHandler.apiKey(Map.of(LambdaHandler.KEY_PARAMETER, "a",
                LambdaHandler.KEY_SECRET, "b"), unasked))
                .hasMessageContaining("exactly one");
        assertThatThrownBy(() -> new LambdaHandler(Map.of()))
                .hasMessageContaining(LambdaHandler.KEY_PARAMETER);
    }

    @Test
    void theQueryTimeoutIsConfigured() {
        assertThat(LambdaHandler.queryTimeout(Map.of())).isEqualTo(ScriptRunner.DEFAULT_QUERY_TIMEOUT);
        assertThat(LambdaHandler.queryTimeout(Map.of(LambdaHandler.QUERY_TIMEOUT, "120")))
                .isEqualTo(Duration.ofSeconds(120));
        assertThat(LambdaHandler.queryTimeout(Map.of(LambdaHandler.QUERY_TIMEOUT, "0"))).isNull();
        assertThatThrownBy(() -> LambdaHandler.queryTimeout(Map.of(LambdaHandler.QUERY_TIMEOUT, "-1")))
                .hasMessageContaining(LambdaHandler.QUERY_TIMEOUT);
        assertThatThrownBy(() -> LambdaHandler.queryTimeout(Map.of(LambdaHandler.QUERY_TIMEOUT, "ten")))
                .hasMessageContaining("ten");
    }

    /**
     * Two deployments' keys sharing one store: the data a key loads is held under that key's
     * owner, so the other key's caller never finds it.
     */
    @Test
    void loadedDataIsHeldUnderTheApiKeyThatLoadedIt() throws IOException {
        DataKeys keys = new DataKeys(new InMemoryDataStore(), 5);
        LambdaHandler first = new LambdaHandler(new ApiKeyAuthenticator(KEY), new ScriptRunner(), "/mcp", keys);
        LambdaHandler second = new LambdaHandler(new ApiKeyAuthenticator("another-key-987"),
                new ScriptRunner(), "/mcp", keys);
        String load = JSON.writeValueAsString(Map.of("jsonrpc", "2.0", "id", 1, "method", "tools/call",
                "params", Map.of("name", "relix_load", "arguments",
                        Map.of("tables", "T := [\n| a |\n|---|\n| 1 |\n];"))));
        Map<?, ?> loaded = result(first.respond(event(KEY, load), Deadline.after(Duration.ofSeconds(30))));
        String dataKey = (String) ((Map<?, ?>) loaded.get("structuredContent")).get("key");
        String run = JSON.writeValueAsString(Map.of("jsonrpc", "2.0", "id", 2, "method", "tools/call",
                "params", Map.of("name", "relix_run", "arguments",
                        Map.of("data", dataKey, "script", "query { T };"))));

        assertThat(result(first.respond(event(KEY, run), Deadline.after(Duration.ofSeconds(30))))
                .get("isError")).isNotEqualTo(true);
        assertThat(result(second.respond(event("another-key-987", run), Deadline.after(Duration.ofSeconds(30))))
                .get("isError")).isEqualTo(true);
    }

    @Test
    void noTableNamedMeansNoDataKeys() {
        assertThat(LambdaHandler.dataKeys(Map.of())).isNull();
        assertThat(LambdaHandler.dataKeys(Map.of(DynamoDbDataStore.TABLE_VARIABLE, "relix-mcp-data",
                "AWS_REGION", "us-east-1", DataKeys.MAX_KEYS_VARIABLE, "7")).maxKeys()).isEqualTo(7);
        assertThatThrownBy(() -> LambdaHandler.dataKeys(Map.of(DynamoDbDataStore.TABLE_VARIABLE, "t",
                "AWS_REGION", "us-east-1", DataKeys.MAX_KEYS_VARIABLE, "0")))
                .isInstanceOf(IllegalStateException.class);
    }

    private static Map<?, ?> result(StatelessHttpTransport.Response response) throws IOException {
        assertThat(response.status()).as(response.body()).isEqualTo(200);
        return (Map<?, ?>) JSON.readValue(response.body(), Map.class).get("result");
    }

    private Map<?, ?> invoke(Map<String, Object> event, Duration remaining) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        handler.handleRequest(new ByteArrayInputStream(JSON.writeValueAsString(event)
                .getBytes(StandardCharsets.UTF_8)), out, new FakeContext(remaining));
        return JSON.readValue(out.toString(StandardCharsets.UTF_8), Map.class);
    }

    /** A Function URL's event, payload format 2.0, for a POST to /mcp. */
    private static Map<String, Object> event(String key, String body) {
        Map<String, Object> headers = new LinkedHashMap<>();
        headers.put("accept", "application/json, text/event-stream");
        headers.put("content-type", "application/json");
        if (key != null) {
            headers.put("x-api-key", key);
        }
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("version", "2.0");
        event.put("rawPath", "/mcp");
        event.put("headers", headers);
        event.put("requestContext", Map.of("http", Map.of("method", "POST", "path", "/mcp")));
        event.put("body", body);
        event.put("isBase64Encoded", false);
        return event;
    }

    private static String call(int id, String tool, String script) throws IOException {
        Map<String, Object> arguments = tool.equals("relix_run") ? Map.of("script", script) : Map.of();
        return JSON.writeValueAsString(Map.of("jsonrpc", "2.0", "id", id, "method", "tools/call",
                "params", Map.of("name", tool, "arguments", arguments)));
    }

    /** A Lambda context whose clock started when it was made. */
    private static final class FakeContext implements Context {

        private final long end;

        FakeContext(Duration remaining) {
            this.end = System.nanoTime() + remaining.toNanos();
        }

        @Override
        public int getRemainingTimeInMillis() {
            return (int) Math.max(0, (end - System.nanoTime()) / 1_000_000);
        }

        @Override
        public String getAwsRequestId() {
            return "test";
        }

        @Override
        public String getLogGroupName() {
            return "test";
        }

        @Override
        public String getLogStreamName() {
            return "test";
        }

        @Override
        public String getFunctionName() {
            return "relix-mcp";
        }

        @Override
        public String getFunctionVersion() {
            return "$LATEST";
        }

        @Override
        public String getInvokedFunctionArn() {
            return "arn:aws:lambda:eu-west-1:000000000000:function:relix-mcp";
        }

        @Override
        public CognitoIdentity getIdentity() {
            return null;
        }

        @Override
        public ClientContext getClientContext() {
            return null;
        }

        @Override
        public int getMemoryLimitInMB() {
            return 1024;
        }

        @Override
        public LambdaLogger getLogger() {
            return null;
        }
    }
}
