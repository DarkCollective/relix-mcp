package com.darkcollective.relix.mcp;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Starts the fat jar the way a deployment would, {@code java -jar}, and calls each tool
 * over HTTP.
 *
 * <p>What it guards is the merge. The engine finds its function library, its connectors
 * and its reference pages by {@code ServiceLoader} and resources, and a jar assembled from
 * forty can lose any of them without failing to compile, start or answer {@code initialize}.
 * So each call here needs one: {@code Round} the function library, a {@code jdbc} URL the
 * standard connectors and the Postgres dialect, {@code learn} the bundled pages. Run by
 * {@code fatJarTest}, which builds the jar first.
 */
@Tag("fat-jar")
class FatJarTest {

    private static final Pattern SERVING = Pattern.compile("serving MCP at (\\S+)");

    private static Process server;
    private static URI endpoint;
    private final HttpClient client = HttpClient.newBuilder().proxy(HttpClient.Builder.NO_PROXY).build();

    @BeforeAll
    static void start() throws Exception {
        Path jar = Path.of(System.getProperty("relix.mcp.fatJar", "(fatJar not set)"));
        assertThat(jar).as("run through the fatJarTest task, which builds it").isRegularFile();
        Path java = Path.of(System.getProperty("java.home"), "bin", "java");
        server = new ProcessBuilder(java.toString(), "-jar", jar.toString(), "--http", "--port", "0")
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .start();
        BufferedReader err = new BufferedReader(
                new InputStreamReader(server.getErrorStream(), StandardCharsets.UTF_8));
        StringBuilder seen = new StringBuilder();
        for (String line; (line = err.readLine()) != null; ) {
            seen.append(line).append('\n');
            Matcher serving = SERVING.matcher(line);
            if (serving.find()) {
                endpoint = URI.create(serving.group(1));
                break;
            }
        }
        assertThat(endpoint).as("the server's first words:\n" + seen).isNotNull();
    }

    @AfterAll
    static void stop() throws InterruptedException {
        if (server != null) {
            server.destroy();
            server.waitFor(10, TimeUnit.SECONDS);
        }
    }

    @Test
    void initializeNamesTheServerAndTheEngine() throws Exception {
        String body = post("""
                {"jsonrpc":"2.0","id":1,"method":"initialize","params":{
                 "protocolVersion":"2025-06-18","capabilities":{},
                 "clientInfo":{"name":"fat-jar-test","version":"1"}}}""");

        assertThat(body).contains("\"name\":\"relix\"").contains("engine");
    }

    @Test
    void runCallsAFunctionFromTheLibrary() throws Exception {
        String body = call("run", "{\"script\":\"query { PROJECT Round(2.6) -> r (UNIT) };\"}");

        assertThat(body).contains("| r |").contains("| 3 |");
    }

    @Test
    void explainRendersSqlForAPostgresConnection() throws Exception {
        String body = call("explain", """
                {"script":"connection wh from jdbc { url: \\"jdbc:postgresql://db.invalid/wh\\" };\\n\
                query { SELECT amount > 100 (wh.orders) };",
                 "catalog":{"wh":{"orders":{"amount":"NUMBER"}}}}""");

        assertThat(body).contains("PushedScan").contains("WHERE");
    }

    @Test
    void learnReadsTheBundledPages() throws Exception {
        assertThat(call("learn", "{\"topic\":\"σ\"}")).contains("Selection");
        assertThat(call("learn", "{\"topic\":\"round\"}")).contains("Round");
    }

    private String call(String tool, String arguments) throws Exception {
        return post("{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/call\",\"params\":{\"name\":\""
                + tool + "\",\"arguments\":" + arguments + "}}");
    }

    private String post(String body) throws Exception {
        HttpResponse<String> response = client.send(HttpRequest.newBuilder(endpoint)
                        .header("Accept", "application/json, text/event-stream")
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        return response.body();
    }
}
