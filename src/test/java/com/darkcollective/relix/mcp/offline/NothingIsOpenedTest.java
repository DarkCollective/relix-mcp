package com.darkcollective.relix.mcp.offline;

import com.darkcollective.relix.mcp.data.LoadedData;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Driver;
import java.sql.DriverManager;
import java.sql.DriverPropertyInfo;
import java.sql.SQLException;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Validation reaches nothing a script names: not a database, not an HTTP endpoint, not a
 * file on this machine. Each resource here counts every attempt to use it, and the counts
 * stay at zero.
 */
class NothingIsOpenedTest {

    private final ScriptValidator validator = new ScriptValidator();
    private final ScriptExplainer explainer = new ScriptExplainer();

    private final AtomicInteger connects = new AtomicInteger();
    private final AtomicInteger requests = new AtomicInteger();
    private Driver driver;
    private HttpServer http;
    private String base;

    @BeforeEach
    void listen() throws Exception {
        driver = new CountingDriver(connects);
        DriverManager.registerDriver(driver);
        http = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        http.createContext("/", exchange -> {
            requests.incrementAndGet();
            byte[] body = "id,name\n1,a\n".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        http.start();
        base = "http://127.0.0.1:" + http.getAddress().getPort();
    }

    @AfterEach
    void stop() throws SQLException {
        http.stop(0);
        DriverManager.deregisterDriver(driver);
    }

    @Test
    void aDatabaseIsNeverConnectedTo() {
        String script = """
                connection wh from jdbc { url: "jdbc:counting:wh" };
                source Orders from wh { table: "orders", schema: { id: NUMBER } };
                query { PROJECT id (Orders) };
                query { PROJECT name (wh.customers) };
                query { wh.unknown };
                """;
        CallerCatalog catalog = CallerCatalog.of(Map.of("wh",
                Map.of("customers", Map.of("name", "STRING"))));

        ValidationReport report = validator.validate(script, Map.of(), catalog);
        ExplainReport explained =
                explainer.explain(script.replace("query { wh.unknown };\n", ""), Map.of(), catalog);

        assertThat(report.render()).contains("unknown");
        assertThat(explained.queries()).hasSize(2).allSatisfy(q -> assertThat(q.planned()).isTrue());
        assertThat(connects).hasValue(0);
    }

    @Test
    void loadedDataOpensNothingEither() {
        LoadedData data = LoadedData.of("""
                Ids := [
                | id |
                |----|
                | 1  |
                ];
                """);
        String script = """
                connection wh from jdbc { url: "jdbc:counting:wh" };
                query { Ids JOIN wh.customers };
                """;
        CallerCatalog catalog = CallerCatalog.of(Map.of("wh",
                Map.of("customers", Map.of("id", "NUMBER", "name", "STRING"))));

        ValidationReport report = validator.validate(script, Map.of(), catalog, data);
        ExplainReport explained = explainer.explain(script, Map.of(), catalog, data);

        assertThat(report.valid()).as(report.render()).isTrue();
        assertThat(explained.queries()).singleElement().satisfies(q -> assertThat(q.planned()).isTrue());
        assertThat(connects).hasValue(0);
    }

    @Test
    void anHttpEndpointIsNeverRequested() {
        String script = """
                source Open from http { url: "%1$s/open" };
                source Typed from http { url: "%1$s/typed", schema: { id: NUMBER at "$.id" } };
                connection remote from csv { url: "%1$s/data.csv" };
                query { PROJECT id (Typed) };
                query { PROJECT id (Open) };
                query { remote.data };
                """.formatted(base);

        validator.validate(script, Map.of(), CallerCatalog.empty());
        ExplainReport explained = explainer.explain(script, Map.of(), CallerCatalog.of(Map.of(
                "remote", Map.of("data", Map.of("id", "NUMBER")))));

        assertThat(explained.render()).as(explained.render()).contains("Plan:");
        assertThat(requests).hasValue(0);
    }

    @Test
    void aLocalFileIsNeverRead(@TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve("people.csv"), "id,name\n1,a\n");
        String script = """
                connection disk from csv { path: "%s" };
                query { PROJECT name (disk.people) };
                """.formatted(dir.toString().replace("\\", "/"));

        ValidationReport report = validator.validate(script, Map.of(), CallerCatalog.empty());

        // Had the file been read, its header would have resolved the table. It is
        // unknown instead, because the only catalog is the caller's, and it is empty.
        assertThat(report.valid()).isFalse();
        assertThat(report.render()).contains("people");
    }

    @Test
    void anImportIsNeverReadFromDisk(@TempDir Path dir) throws IOException {
        Path lib = dir.resolve("lib.relix");
        Files.writeString(lib, "X := [\n| a |\n|---|\n| 1 |\n];\n");
        String script = "import \"%s\";\nquery { X };".formatted(lib.toString().replace("\\", "/"));

        ValidationReport report = validator.validate(script, Map.of(), CallerCatalog.empty());

        assertThat(report.valid()).isFalse();
    }

    private static final String SENTINEL = "sentinel_first_line_7f3a";

    private String everyAnswer(String script, Map<String, String> files) {
        String validated = validator.validate(script, files, CallerCatalog.empty()).render();
        String explained = explainer.explain(script, files, CallerCatalog.empty()).render();
        String ran = new com.darkcollective.relix.mcp.run.ScriptRunner().run(script).render();
        return validated + "\n" + explained + "\n" + ran;
    }

    private static String slashed(Path path) {
        return path.toString().replace("\\", "/");
    }

    private void assertNothingRead(String answers, Path dir) {
        assertThat(answers).doesNotContain(SENTINEL).doesNotContain(dir.toString());
    }

    @Test
    void aSchemalessCsvIsNotReadAndTheModelIsToldToDeclareASchema(@TempDir Path dir)
            throws IOException {
        Path file = dir.resolve("c.csv");
        Files.writeString(file, SENTINEL + ",b\n1,2\n");
        String script = "source C from csv(\"%s\") { header: true }; query { PROJECT a (C) };"
                .formatted(slashed(file));

        assertThat(validator.validate(script, Map.of(), CallerCatalog.empty()).render())
                .contains("declare").contains("schema");
        assertThat(explainer.explain(script, Map.of(), CallerCatalog.empty()).render())
                .contains("declare").contains("schema");
        assertNothingRead(everyAnswer(script, Map.of()), dir);
    }

    @Test
    void aMissingRelativeCsvDoesNotLeakTheWorkingDirectory() {
        String script = "source C from csv(\"nowhere.csv\") { header: true }; "
                + "query { PROJECT a (C) };";

        String answers = everyAnswer(script, Map.of());

        assertThat(answers).doesNotContain(Path.of("").toAbsolutePath().toString());
        assertThat(validator.validate(script, Map.of(), CallerCatalog.empty()).valid()).isFalse();
    }

    @Test
    void aCsvWithADeclaredSchemaIsStillNotRead(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("c.csv");
        Files.writeString(file, SENTINEL + ",b\n1,2\n");
        String script = "source C from csv(\"%s\") { header: true, schema: { a: NUMBER } }; "
                .formatted(slashed(file)) + "query { PROJECT a (C) };";

        ValidationReport report = validator.validate(script, Map.of(), CallerCatalog.empty());

        assertThat(report.valid()).as(report.render()).isTrue();
        assertThat(explainer.explain(script, Map.of(), CallerCatalog.empty()).render())
                .doesNotContain(SENTINEL);
    }

    @Test
    void aJsonFileIsNotRead(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("d.json");
        Files.writeString(file, "[{\"" + SENTINEL + "\": 1}]");
        String script = "source J from json(\"%s\"); query { PROJECT a (J) };"
                .formatted(slashed(file));

        assertNothingRead(everyAnswer(script, Map.of()), dir);
    }

    @Test
    void aSchemalessCsvInAnImportedFileIsRefused(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("c.csv");
        Files.writeString(file, SENTINEL + ",b\n1,2\n");
        Map<String, String> files = Map.of("lib.relix",
                "source C from csv(\"%s\") { header: true };".formatted(slashed(file)));
        String script = "import \"lib.relix\"; query { PROJECT a (C) };";

        ValidationReport report = validator.validate(script, files, CallerCatalog.empty());

        assertThat(report.valid()).isFalse();
        assertThat(report.render()).contains("declare").contains("lib.relix");
        assertNothingRead(everyAnswer(script, files), dir);
    }

    @Test
    void aFileConnectionTableIsNotRead(@TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve("people.csv"), SENTINEL + ",b\n1,2\n");
        String script = """
                connection disk from csv { path: "%s" };
                source P from disk { table: "people" };
                query { PROJECT a (P) };
                """.formatted(slashed(dir));

        assertNothingRead(everyAnswer(script, Map.of()), dir);
    }

    /** A JDBC driver for {@code jdbc:counting:} URLs that counts connection attempts. */
    private record CountingDriver(AtomicInteger connects) implements Driver {

        @Override
        public Connection connect(String url, Properties info) throws SQLException {
            if (!acceptsURL(url)) {
                return null;
            }
            connects.incrementAndGet();
            throw new SQLException("validation must not connect");
        }

        @Override
        public boolean acceptsURL(String url) {
            return url.startsWith("jdbc:counting:");
        }

        @Override
        public DriverPropertyInfo[] getPropertyInfo(String url, Properties info) {
            return new DriverPropertyInfo[0];
        }

        @Override
        public int getMajorVersion() {
            return 1;
        }

        @Override
        public int getMinorVersion() {
            return 0;
        }

        @Override
        public boolean jdbcCompliant() {
            return false;
        }

        @Override
        public Logger getParentLogger() {
            return Logger.getGlobal();
        }
    }
}
