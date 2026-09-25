package com.darkcollective.relix.mcp.run;

import com.darkcollective.relix.embed.Sandbox;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class ScriptRunnerTest {

    private static final String FLIGHTS = """
            Flights := [
            | origin | dest |
            |--------|------|
            | LHR    | CDG  |
            | CDG    | FRA  |
            | FRA    | SIN  |
            | SIN    | SYD  |
            | LHR    | DXB  |
            | DXB    | SIN  |
            ];
            """;

    private final ScriptRunner runner = new ScriptRunner();

    @Test
    void aScriptOverItsOwnTablesRunsAndReturnsItsRows() {
        RunReport report = runner.run(FLIGHTS + """
                Reachable := { CLOSURE origin, dest (Flights) };
                query { SORT origin, dest (Reachable) };
                """);

        assertThat(report.valid()).isTrue();
        QueryResult result = report.results().getFirst();
        assertThat(result.columns()).containsExactly("origin", "dest");
        assertThat(result.rows()).hasSize(13)
                .startsWith(List.of("CDG", "FRA"))
                .endsWith(List.of("SIN", "SYD"));
        assertThat(result.truncated()).isFalse();
        assertThat(report.render()).startsWith("Query 1:\n| origin | dest |");
    }

    @Test
    void numbersBooleansAndNullsKeepTheirKind() {
        RunReport report = runner.run("""
                T := [
                | n   | s | z |
                |-----|---|---|
                | 1.5 | x |   |
                ];
                query { PROJECT n, IIf(n > 1, true, false) -> b, s, z (T) };
                """);

        assertThat(report.results().getFirst().rows().getFirst())
                .containsExactly(new BigDecimal("1.5"), true, "x", null);
    }

    @Test
    void eachQueryIsAResultInSourceOrder() {
        RunReport report = runner.run(FLIGHTS + """
                Direct := { SELECT origin = 'LHR' (Flights) };
                query Direct;
                query { GROUP origin, COUNT(*) -> n (Flights) };
                """);

        assertThat(report.results()).hasSize(2);
        assertThat(report.results().getFirst().name()).isEqualTo("Direct");
        assertThat(report.results().get(1).name()).isNull();
        assertThat(report.results().get(1).columns()).containsExactly("origin", "n");
        assertThat(report.render()).contains("Query 1 (Direct):").contains("Query 2:");
    }

    @Test
    void anInvalidScriptRunsNothingAndSaysWhy() {
        RunReport report = runner.run(FLIGHTS + "query { PROJECT origin, dst (Flights) };");

        assertThat(report.valid()).isFalse();
        assertThat(report.results()).isEmpty();
        assertThat(report.render()).startsWith("Invalid, so nothing ran:").contains("dst");
    }

    @Test
    void aScriptWithNoQuerySaysSo() {
        assertThat(runner.run(FLIGHTS).render()).contains("no query statement");
    }

    @Test
    void anExternalSourceIsRefusedAndNeverRequested() throws IOException {
        AtomicInteger requests = new AtomicInteger();
        HttpServer http = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        http.createContext("/", exchange -> {
            requests.incrementAndGet();
            exchange.sendResponseHeaders(500, -1);
            exchange.close();
        });
        http.start();
        try {
            String base = "http://127.0.0.1:" + http.getAddress().getPort();
            RunReport report = runner.run("""
                    source W from http { url: "%s/w", schema: { t: NUMBER at "$.t" } };
                    query { W };
                    """.formatted(base));

            assertThat(report.valid()).isFalse();
            assertThat(report.render()).contains("does not permit the source 'W'");
        } finally {
            http.stop(0);
        }
        assertThat(requests).hasValue(0);
    }

    @Test
    void aLocalFileAndAnImportAreRefused(@TempDir Path dir) throws IOException {
        Path csv = dir.resolve("p.csv");
        Files.writeString(csv, "id\n1\n");
        Path lib = dir.resolve("lib.relix");
        Files.writeString(lib, "X := [\n| a |\n|---|\n| 1 |\n];\n");

        RunReport file = runner.run("""
                source P from csv("%s") { header: true, schema: { id: NUMBER } };
                query { P };
                """.formatted(csv.toString().replace("\\", "/")));
        RunReport imported = runner.run(
                "import \"%s\";\nquery { X };".formatted(lib.toString().replace("\\", "/")));

        assertThat(file.valid()).isFalse();
        assertThat(imported.valid()).isFalse();
        assertThat(file.render()).contains("does not permit the source 'P'");
        assertThat(imported.render()).contains("does not permit an import");
    }

    @Test
    void aLongResultIsCutAndSaysSo() {
        ScriptRunner small = new ScriptRunner(Sandbox.builder().maxOutputRows(4).build());

        RunReport report = small.run(FLIGHTS + "query { Flights };");

        QueryResult result = report.results().getFirst();
        assertThat(result.rows()).hasSize(4);
        assertThat(result.truncated()).isTrue();
        assertThat(report.render()).contains("cut at the row limit");
    }

    @Test
    void tooMuchWorkStopsTheQueryButNotTheOthers() {
        ScriptRunner small = new ScriptRunner(Sandbox.builder().maxProcessedRows(50).build());

        RunReport report = small.run(FLIGHTS + """
                query { Flights CROSS (RENAME (origin -> o2, dest -> d2) (Flights)) CROSS (RENAME (origin -> o3, dest -> d3) (Flights)) };
                query { SELECT origin = 'LHR' (Flights) };
                """);

        assertThat(report.results().getFirst().ran()).isFalse();
        assertThat(report.results().getFirst().failure()).contains("50");
        assertThat(report.results().get(1).ran()).isTrue();
    }

    @Test
    void anOpenSandboxIsRefused() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> new ScriptRunner(Sandbox.open()))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
