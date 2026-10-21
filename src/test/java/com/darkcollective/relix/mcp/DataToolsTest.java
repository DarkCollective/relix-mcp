package com.darkcollective.relix.mcp;

import com.darkcollective.relix.mcp.data.DataKeys;
import com.darkcollective.relix.mcp.data.InMemoryDataStore;
import com.darkcollective.relix.mcp.http.Authenticator;
import com.darkcollective.relix.mcp.http.StatelessHttpTransport;
import com.darkcollective.relix.mcp.run.ScriptRunner;
import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.server.transport.ServerTransportSecurityValidator;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code relix_load}, {@code relix_unload}, and the {@code data} argument of the four script
 * tools.
 */
class DataToolsTest {

    private static final String ORDERS = """
            Orders := [
            | order_id | customer_id | amount |
            |----------|-------------|--------|
            | 1        | 1           | 120    |
            | 2        | 2           | 50     |
            | 3        | 1           | 300    |
            ];
            Words := [
            | w |
            |---|
            | a |
            | "b" |
            ];
            """;

    private final Tools tools = new Tools(new ScriptRunner(),
            new DataKeys(new InMemoryDataStore(), 2));

    @Test
    void theDataToolsAreListedOnlyByAServerThatKeepsData() {
        assertThat(tools.all()).extracting(t -> t.tool().name()).containsExactly("relix_validate",
                "relix_explain", "relix_run", "relix_submit", "relix_learn", "relix_load", "relix_unload");
        assertThat(properties(tools.runTool())).contains("data");
        assertThat(tools.runTool().tool().description()).endsWith(Tools.DATA_NOTE);

        Tools without = new Tools();
        assertThat(without.all()).extracting(t -> t.tool().name()).doesNotContain("relix_load");
        assertThat(properties(without.runTool())).doesNotContain("data");
        assertThat(text(without.run(call("relix_run", Map.of("script", "query { Orders };",
                "data", "abc"))))).contains("holds no loaded data");
    }

    @Test
    void aScriptReadsLoadedTablesWithoutDeclaringThem() {
        String key = load(ORDERS);

        CallToolResult run = tools.run(call("relix_run", Map.of("data", key,
                "script", "query { SELECT amount > 100 (Orders) };")));

        assertThat(run.isError()).isNotEqualTo(true);
        assertThat(text(run)).contains("| 1 ").contains("| 3 ").doesNotContain("| 2 ");
        CallToolResult submit = tools.submit(call("relix_submit", Map.of("data", key,
                "script", "query { SELECT amount > 100 (Orders) };")));
        Map<?, ?> submitted = (Map<?, ?>) submit.structuredContent();
        assertThat(submitted.get("submitted")).isEqualTo(true);
        assertThat(submitted.get("data")).isEqualTo(key);
        assertThat(text(submit)).as("the data is not echoed").doesNotContain("customer_id |---");
        assertThat(text(tools.explain(call("relix_explain", Map.of("data", key,
                "script", "query { SELECT amount > 100 (Orders) };"))))).contains("Orders");
    }

    @Test
    void findingsInTheScriptAreOnTheScriptsOwnLines() {
        String key = load(ORDERS);

        CallToolResult result = tools.validate(call("relix_validate", Map.of("data", key,
                "script", "Big := { SELECT amount > 100 (Orders) };\nquery { PROJECT nope (Big) };")));

        Map<?, ?> data = (Map<?, ?>) result.structuredContent();
        assertThat(data.get("valid")).isEqualTo(false);
        assertThat((List<?>) data.get("findings")).singleElement().satisfies(f -> {
            Map<?, ?> finding = (Map<?, ?>) f;
            assertThat(finding.get("file")).isNull();
            assertThat(finding.get("line")).isEqualTo(2);
        });
        assertThat(text(result)).contains("script:2:").contains("\n  query { PROJECT nope (Big) };");
    }

    @Test
    void aDeclarationOfALoadedTableIsReportedAndIgnored() {
        String key = load(ORDERS);

        CallToolResult run = tools.run(call("relix_run", Map.of("data", key, "script", """
                query { GROUP COUNT(*) -> n (Orders) };
                Orders := [
                | order_id | customer_id | amount |
                |----------|-------------|--------|
                | 1        | 1           | 120    |
                ];
                """)));

        Map<?, ?> data = (Map<?, ?>) run.structuredContent();
        assertThat(data.get("valid")).isEqualTo(true);
        assertThat((List<?>) data.get("findings")).singleElement().satisfies(f -> {
            Map<?, ?> finding = (Map<?, ?>) f;
            assertThat(finding.get("severity")).isEqualTo("warning");
            assertThat(finding.get("line")).isEqualTo(2);
            assertThat((String) finding.get("message")).startsWith("'Orders' is loaded data");
        });
        assertThat(text(run)).as("the loaded table is read").contains("| 3 |");
    }

    /** Relix names are case-insensitive, so a copy in another case is still a copy. */
    @Test
    void aCopyOfALoadedTableInAnotherCaseIsIgnoredToo() {
        String key = load(ORDERS);

        CallToolResult run = tools.run(call("relix_run", Map.of("data", key, "script", """
                ORDERS := [
                | order_id | customer_id | amount |
                |----------|-------------|--------|
                | 1        | 1           | 120    |
                ];
                query { GROUP COUNT(*) -> n (orders) };
                """)));

        assertThat(text(run)).contains("'ORDERS' is the loaded table 'Orders' (names are "
                + "case-insensitive), so this declaration of it was ignored").contains("| 3 |");
    }

    /**
     * A view named after a loaded table is left to the engine, which reports it, rather than
     * left out, which would read the whole table in its place.
     */
    @Test
    void aViewNamedAfterALoadedTableIsLeftToTheEngine() {
        String key = load(ORDERS);

        CallToolResult result = tools.validate(call("relix_validate", Map.of("data", key,
                "script", "orders := { SELECT amount > 100 (Orders) };\nquery orders;")));

        Map<?, ?> data = (Map<?, ?>) result.structuredContent();
        assertThat(data.get("valid")).isEqualTo(false);
        assertThat(text(result)).doesNotContain("is loaded data");
    }

    @Test
    void aMistypedKeyIsAnsweredWithTheKeyItWasMeantToBe() {
        String key = load(ORDERS);
        String typo = (key.charAt(0) == 'a' ? "b" : "a") + key.substring(1);

        CallToolResult run = tools.run(call("relix_run", Map.of("data", typo, "script", "query { Orders };")));

        assertThat(run.isError()).isTrue();
        assertThat(text(run)).contains("this caller holds '" + key + "'");
    }

    @Test
    void aShortenedKeyIsToldToBeSentInFull() {
        String key = load(ORDERS);

        CallToolResult run = tools.run(call("relix_run", Map.of("data", key.substring(0, 8) + "…",
                "script", "query { Orders };")));

        assertThat(run.isError()).isTrue();
        assertThat(text(run)).isEqualTo("'" + key.substring(0, 8) + "…' is not a whole data key: "
                + "a data key is 32 hexadecimal characters, exactly as 'relix_load' gave it. "
                + "Send it in full, not shortened.");
    }

    @Test
    void theDatasWarningsAreOneLineInTheirOwnField() {
        String key = load(ORDERS);

        for (CallToolResult result : List.of(
                tools.validate(call("relix_validate", Map.of("data", key, "script", "query { Orders };"))),
                tools.explain(call("relix_explain", Map.of("data", key, "script", "query { Orders };"))),
                tools.run(call("relix_run", Map.of("data", key, "script", "query { Orders };"))),
                tools.submit(call("relix_submit", Map.of("data", key, "script", "query { Orders };"))))) {
            Map<?, ?> data = (Map<?, ?>) result.structuredContent();
            assertThat(data.get("findings")).as("not among the script's findings").isEqualTo(List.of());
            assertThat((String) data.get("dataWarnings"))
                    .startsWith("The loaded data has 1 warning, in Words.");
            assertThat(text(result)).endsWith((String) data.get("dataWarnings"))
                    .doesNotContain("keeps its quotes");
        }
    }

    @Test
    void aLoadAtTheKeyLimitIsRefused() {
        load(ORDERS);
        load(ORDERS);

        CallToolResult refused = tools.load(call("relix_load", Map.of("tables", ORDERS)));

        assertThat(refused.isError()).isTrue();
        assertThat(text(refused)).contains("already holds 2 data keys").contains("'relix_unload'");
    }

    @Test
    void aReadOnlyKeyRefusesALoadThroughTheTool() {
        CallToolResult loaded = tools.load(call("relix_load", Map.of("tables", ORDERS, "read_only", true)));
        String key = (String) ((Map<?, ?>) loaded.structuredContent()).get("key");
        assertThat(((Map<?, ?>) loaded.structuredContent()).get("readOnly")).isEqualTo(true);

        CallToolResult refused = tools.load(call("relix_load", Map.of("key", key,
                "tables", "Orders := [\n| x |\n|---|\n| 1 |\n];")));

        assertThat(refused.isError()).isTrue();
        assertThat(text(refused)).contains("read-only");
        assertThat(text(tools.run(call("relix_run", Map.of("data", key,
                "script", "query { GROUP COUNT(*) -> n (Orders) };"))))).contains("| 3 |");
    }

    @Test
    void aKeyIsGoneOnceUnloaded() {
        String key = load(ORDERS);

        assertThat(text(tools.unload(call("relix_unload", Map.of("key", key))))).startsWith("Released");
        CallToolResult run = tools.run(call("relix_run", Map.of("data", key, "script", "query { Orders };")));
        assertThat(run.isError()).isTrue();
        assertThat(text(run)).isEqualTo(DataKeys.notFound(key));
    }

    @Test
    void anotherOwnersKeyIsNotFound() {
        String key = load(ORDERS);
        CallToolRequest run = call("relix_run", Map.of("data", key, "script", "query { Orders };"));

        assertThat(tools.run(run, "someone-else", () -> false).isError()).isTrue();
        assertThat(text(tools.run(run, "someone-else", () -> false))).isEqualTo(DataKeys.notFound(key));
        assertThat(tools.run(run, Authenticator.LOCAL, () -> false).isError()).isNotEqualTo(true);
        assertThat(text(tools.load(call("relix_load", Map.of("tables", ORDERS)), null, () -> false)))
                .isEqualTo(Tools.NO_OWNER);
    }

    /**
     * The owner reaches the tools from the request's transport context, as an
     * authenticator puts it there: two owners on one server do not share a key.
     */
    @Test
    void theOwnerComesFromTheRequestsTransportContext() throws IOException {
        StatelessHttpTransport transport = new StatelessHttpTransport(JSON,
                ServerTransportSecurityValidator.NOOP);
        Main.statelessServer(transport, new ScriptRunner(), new DataKeys(new InMemoryDataStore(), 2));
        Deadline deadline = Deadline.after(Duration.ofSeconds(30));

        Map<?, ?> loaded = result(transport.handle("POST", CLIENT, toolCall("relix_load",
                Map.of("tables", ORDERS)), deadline.context(Optional.of("alice"))));
        String key = (String) ((Map<?, ?>) loaded.get("structuredContent")).get("key");
        String run = toolCall("relix_run", Map.of("data", key, "script", "query { Orders };"));

        assertThat(result(transport.handle("POST", CLIENT, run,
                deadline.context(Optional.of("alice")))).get("isError")).isNotEqualTo(true);
        assertThat(result(transport.handle("POST", CLIENT, run,
                deadline.context(Optional.of("bob")))).get("isError")).isEqualTo(true);
        assertThat(result(transport.handle("POST", CLIENT, run,
                McpTransportContext.EMPTY)).get("isError")).isEqualTo(true);
    }

    private static List<String> properties(Tools.Definition tool) {
        return ((Map<?, ?>) tool.tool().inputSchema().get("properties")).keySet().stream()
                .map(String::valueOf).toList();
    }

    private String load(String tables) {
        CallToolResult loaded = tools.load(call("relix_load", Map.of("tables", tables)));
        assertThat(loaded.isError()).as(text(loaded)).isFalse();
        return (String) ((Map<?, ?>) loaded.structuredContent()).get("key");
    }

    private static final McpJsonMapper JSON = McpJsonDefaults.getMapper();
    private static final Map<String, List<String>> CLIENT = Map.of(
            "Accept", List.of("application/json, text/event-stream"),
            "Content-Type", List.of("application/json"));

    private static String toolCall(String name, Map<String, Object> arguments) throws IOException {
        return JSON.writeValueAsString(Map.of("jsonrpc", "2.0", "id", 1, "method", "tools/call",
                "params", Map.of("name", name, "arguments", arguments)));
    }

    private static Map<?, ?> result(StatelessHttpTransport.Response response) throws IOException {
        assertThat(response.status()).as(response.body()).isEqualTo(200);
        return (Map<?, ?>) JSON.readValue(response.body(), Map.class).get("result");
    }

    private static CallToolRequest call(String name, Map<String, Object> args) {
        return CallToolRequest.builder(name).arguments(args).build();
    }

    private static String text(CallToolResult result) {
        return ((TextContent) result.content().getFirst()).text();
    }
}
