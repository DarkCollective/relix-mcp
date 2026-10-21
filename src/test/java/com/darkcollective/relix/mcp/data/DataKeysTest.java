package com.darkcollective.relix.mcp.data;

import com.darkcollective.relix.mcp.offline.Finding;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DataKeysTest {

    static final String ORDERS = """
            Orders := [
            | order_id | customer_id | amount |
            |----------|-------------|--------|
            | 1        | 1           | 120    |
            | 2        | 2           | 50     |
            ];
            """;

    /** A table whose second row quotes a cell, which the engine warns about. */
    static final String WORDS = """
            Words := [
            | w |
            |---|
            | a |
            | "b" |
            ];
            """;

    private final DataKeys keys = new DataKeys(new InMemoryDataStore(), 3);

    @Test
    void aLoadReturnsAKeyAndEachTablesColumnsTypesAndRows() {
        LoadReport report = keys.load("alice", null, ORDERS + WORDS);

        assertThat(report.loaded()).isTrue();
        assertThat(report.key()).matches("[0-9a-f]{32}");
        assertThat(report.tables()).containsExactly(
                new LoadReport.Table("Orders", 2, List.of("order_id", "customer_id", "amount"),
                        List.of("NUMBER", "NUMBER", "NUMBER")),
                new LoadReport.Table("Words", 2, List.of("w"), List.of("STRING")));
        assertThat(report.render()).contains(report.key())
                .contains("Orders, 2 rows: order_id NUMBER, customer_id NUMBER, amount NUMBER");
    }

    @Test
    void theDatasWarningsAreListedInFullOnLoadAndCountedInTheStore() {
        LoadReport report = keys.load("alice", null, ORDERS + WORDS);

        assertThat(report.findings()).singleElement().satisfies(warning -> {
            assertThat(warning.severity()).isEqualTo(Finding.WARNING);
            assertThat(warning.file()).isEqualTo(LoadedData.LABEL);
            assertThat(warning.line()).as("on the caller's own line").isEqualTo(7);
        });
        assertThat(report.render()).contains("1 warning about the data").contains("data:7:");
        StoredData stored = keys.find("alice", report.key()).orElseThrow();
        assertThat(stored.warnings()).isEqualTo(1);
        assertThat(stored.warnedTables()).containsExactly("Words");
        assertThat(stored.warningsLine()).isEqualTo("The loaded data has 1 warning, in Words. "
                + "'relix_load' listed them; call it again with this key and no tables to see "
                + "them again.");
    }

    @Test
    void loadingANameTheKeyHoldsReplacesItAndSaysSo() {
        String key = keys.load("alice", null, ORDERS + WORDS).key();

        LoadReport report = keys.load("alice", key, """
                Orders := [
                | order_id | total |
                |----------|-------|
                | 9        | 1     |
                ];
                Extra := [
                | x |
                |---|
                | 1 |
                ];
                """);

        assertThat(report.key()).isEqualTo(key);
        assertThat(report.replaced()).containsExactly("Orders");
        assertThat(report.tables()).extracting(LoadReport.Table::name)
                .containsExactly("Words", "Orders", "Extra");
        assertThat(report.tables().get(1).columns()).containsExactly("order_id", "total");
        assertThat(report.render()).contains("Replaced what the key held as Orders.");
    }

    @Test
    void aTableLoadedInAnotherCaseReplacesTheOneTheKeyHolds() {
        String key = keys.load("alice", null, ORDERS).key();

        LoadReport report = keys.load("alice", key, "ORDERS := [\n| x |\n|---|\n| 1 |\n];");

        assertThat(report.replaced()).containsExactly("Orders");
        assertThat(report.tables()).extracting(LoadReport.Table::name).containsExactly("ORDERS");
    }

    @Test
    void aKeyAloneDescribesWhatItHolds() {
        String key = keys.load("alice", null, ORDERS + WORDS).key();

        LoadReport again = keys.load("alice", key, null);

        assertThat(again.key()).isEqualTo(key);
        assertThat(again.tables()).extracting(LoadReport.Table::name).containsExactly("Orders", "Words");
        assertThat(again.findings()).hasSize(1);
        assertThat(again.replaced()).isEmpty();
    }

    @Test
    void aReadOnlyKeysTablesCannotBeReplacedOrAddedTo() {
        LoadReport loaded = keys.load("alice", null, ORDERS, true);
        String key = loaded.key();
        assertThat(loaded.readOnly()).isTrue();
        assertThat(loaded.render()).contains(key + ", read-only");

        LoadReport replace = keys.load("alice", key, "Orders := [\n| x |\n|---|\n| 1 |\n];");
        LoadReport add = keys.load("alice", key, WORDS, false);

        assertThat(replace.loaded()).isFalse();
        assertThat(replace.refusal()).contains("read-only");
        assertThat(add.loaded()).isFalse();
        assertThat(keys.find("alice", key).orElseThrow().declarations()).isEqualTo(ORDERS);
        LoadReport described = keys.load("alice", key, null);
        assertThat(described.readOnly()).as("a key that is read-only stays so").isTrue();
        assertThat(described.tables()).extracting(LoadReport.Table::rows).containsExactly(2);
        assertThat(keys.unload("alice", key)).as("its owner can still release it").isTrue();
    }

    @Test
    void aKeyCanBeMadeReadOnlyOnceItHoldsItsTables() {
        String key = keys.load("alice", null, ORDERS).key();

        assertThat(keys.load("alice", key, WORDS, true).readOnly()).isTrue();

        assertThat(keys.load("alice", key, ORDERS).loaded()).isFalse();
    }

    @Test
    void anotherOwnersKeyIsNotFound() {
        String key = keys.load("alice", null, ORDERS).key();

        assertThat(keys.find("bob", key)).isEmpty();
        assertThat(keys.load("bob", key, null).refusal()).isEqualTo(DataKeys.notFound(key));
        assertThat(keys.unload("bob", key)).isFalse();
        assertThat(keys.find("alice", key)).isPresent();
    }

    @Test
    void aLoadAtTheLimitIsRefusedAndNothingIsEvicted() {
        List<String> held = List.of(keys.load("alice", null, ORDERS).key(),
                keys.load("alice", null, ORDERS).key(), keys.load("alice", null, ORDERS).key());

        LoadReport refused = keys.load("alice", null, ORDERS);

        assertThat(refused.loaded()).isFalse();
        assertThat(refused.render()).contains("already holds 3 data keys").contains("relix_unload");
        assertThat(held).allSatisfy(key -> assertThat(keys.find("alice", key)).isPresent());
        assertThat(keys.load("alice", held.getFirst(), WORDS).loaded())
                .as("adding to a key held takes no new one").isTrue();
        assertThat(keys.load("bob", null, ORDERS).loaded()).as("the limit is per owner").isTrue();

        assertThat(keys.unload("alice", held.getFirst())).isTrue();
        assertThat(keys.load("alice", null, ORDERS).loaded()).isTrue();
    }

    @Test
    void onlyInlineTablesAreLoaded() {
        LoadReport report = keys.load("alice", null, ORDERS + """
                Big := { SELECT amount > 100 (Orders) };
                source C from csv("c.csv") { header: true, schema: { a: NUMBER } };
                """);

        assertThat(report.loaded()).isFalse();
        assertThat(report.findings()).extracting(Finding::line).containsExactly(7, 8);
        assertThat(report.render()).contains("only inline tables can be loaded")
                .contains("Put 'Big' in the script instead");
    }

    @Test
    void dataThatDoesNotParseOrCheckIsNotLoaded() {
        assertThat(keys.load("alice", null, "Orders := [\n| a |\n|---|\n| 1 | 2 |\n];")
                .findings()).singleElement().satisfies(f -> assertThat(f.isError()).isTrue());
        assertThat(keys.load("alice", null, "-- nothing here\n").refusal()).contains("declares no tables");
        assertThat(keys.load("alice", null, null).refusal()).contains("'tables' is required");
    }

    @Test
    void aKeyHoldsAtMostItsLimitInBytes() {
        StringBuilder big = new StringBuilder("Big := [\n| s |\n|---|\n");
        while (big.length() <= DataKeys.MAX_BYTES) {
            big.append("| ").append("x".repeat(1000)).append(" |\n");
        }
        big.append("];\n");

        LoadReport report = keys.load("alice", null, big.toString());

        assertThat(report.loaded()).isFalse();
        assertThat(report.refusal()).contains("a key holds at most " + DataKeys.MAX_BYTES);
    }

    @Test
    void theLimitIsReadFromTheEnvironmentAndOnlyAPositiveWholeNumberIsTaken() {
        assertThat(DataKeys.maxKeys(Map.of())).isEqualTo(DataKeys.DEFAULT_MAX_KEYS);
        assertThat(DataKeys.maxKeys(Map.of(DataKeys.MAX_KEYS_VARIABLE, " 5 "))).isEqualTo(5);
        for (String wrong : List.of("0", "-1", "2.5", "many", "")) {
            assertThatThrownBy(() -> DataKeys.maxKeys(Map.of(DataKeys.MAX_KEYS_VARIABLE, wrong)))
                    .as(wrong).isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining(DataKeys.MAX_KEYS_VARIABLE);
        }
    }
}
