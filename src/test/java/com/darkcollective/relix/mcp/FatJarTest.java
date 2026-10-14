package com.darkcollective.relix.mcp;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

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
        server = serve("-jar", jar.toString());
        endpoint = endpointOf(server);
    }

    /**
     * Starts the server with {@code java}, {@code launch} naming the classes, over HTTP on a
     * free port.
     */
    private static Process serve(String... launch) throws Exception {
        Path java = Path.of(System.getProperty("java.home"), "bin", "java");
        List<String> command = new ArrayList<>(List.of(java.toString()));
        command.addAll(List.of(launch));
        command.addAll(List.of("--http", "--port", "0"));
        return new ProcessBuilder(command).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
    }

    /** Where {@code process} says it is serving, read from its first words. */
    private static URI endpointOf(Process process) throws Exception {
        BufferedReader err = new BufferedReader(
                new InputStreamReader(process.getErrorStream(), StandardCharsets.UTF_8));
        StringBuilder seen = new StringBuilder();
        for (String line; (line = err.readLine()) != null; ) {
            seen.append(line).append('\n');
            Matcher serving = SERVING.matcher(line);
            if (serving.find()) {
                return URI.create(serving.group(1));
            }
        }
        throw new AssertionError("the server's first words:\n" + seen);
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

        assertThat(body).contains("\"name\":\"relix\"").contains("engine")
                .contains("\"version\":\"" + System.getProperty("relix.mcp.version") + "\"");
    }

    /**
     * AWS Lambda unpacks the deployment jar and runs its classes from a directory, where no
     * manifest is read; the version must survive that.
     */
    @Test
    void unpackedTheJarStillReportsItsVersion(@TempDir Path unpacked) throws Exception {
        try (ZipFile jar = new ZipFile(System.getProperty("relix.mcp.fatJar"))) {
            for (ZipEntry entry : Collections.list(jar.entries())) {
                Path target = unpacked.resolve(entry.getName()).normalize();
                assertThat(target.startsWith(unpacked)).as(entry.getName()).isTrue();
                if (entry.isDirectory()) {
                    Files.createDirectories(target);
                } else {
                    Files.createDirectories(target.getParent());
                    try (InputStream in = jar.getInputStream(entry)) {
                        Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
                    }
                }
            }
        }
        Process loose = serve("-cp", unpacked.toString(), Main.class.getName());
        try {
            String body = post(endpointOf(loose), """
                    {"jsonrpc":"2.0","id":1,"method":"initialize","params":{
                     "protocolVersion":"2025-06-18","capabilities":{},
                     "clientInfo":{"name":"fat-jar-test","version":"1"}}}""");

            assertThat(body).contains("\"version\":\"" + System.getProperty("relix.mcp.version") + "\"");
        } finally {
            loose.destroy();
            loose.waitFor(10, TimeUnit.SECONDS);
        }
    }

    @Test
    void runCallsAFunctionFromTheLibrary() throws Exception {
        String body = call("run", "{\"script\":\"query { PROJECT Round(2.6) -> r (UNIT) };\"}");

        assertThat(body).contains("| r NUMBER |").contains("| 3 |");
    }

    @Test
    void submitReturnsTheScriptWithItsRows() throws Exception {
        String body = call("submit", "{\"script\":\"query { PROJECT Round(2.6) -> r (UNIT) };\"}");

        assertThat(body).contains("Submitted.").contains("| r NUMBER |").contains("| 3 |");
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
        assertThat(call("learn", "{\"topic\":\"σ\",\"detail\":\"brief\"}")).contains("# Example:");
    }

    @Test
    void lambdaCanLoadTheHandler() throws Exception {
        try (ZipFile jar = new ZipFile(System.getProperty("relix.mcp.fatJar"))) {
            assertThat(jar.getEntry("com/darkcollective/relix/mcp/aws/LambdaHandler.class")).isNotNull();
            assertThat(jar.getEntry("com/amazonaws/services/lambda/runtime/RequestStreamHandler.class"))
                    .isNotNull();
        }
    }

    private String call(String tool, String arguments) throws Exception {
        return post("{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/call\",\"params\":{\"name\":\""
                + tool + "\",\"arguments\":" + arguments + "}}");
    }

    private String post(String body) throws Exception {
        return post(endpoint, body);
    }

    private String post(URI endpoint, String body) throws Exception {
        HttpResponse<String> response = client.send(HttpRequest.newBuilder(endpoint)
                        .header("Accept", "application/json, text/event-stream")
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        return response.body();
    }
}
