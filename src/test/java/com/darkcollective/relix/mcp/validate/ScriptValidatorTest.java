package com.darkcollective.relix.mcp.validate;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ScriptValidatorTest {

    private final ScriptValidator validator = new ScriptValidator();

    private static final String ORDERS = """
            Orders := [
            | order_id | customer_id | amount |
            |----------|-------------|--------|
            | 1        | 1           | 120    |
            | 2        | 2           | 50     |
            ];
            """;

    private ValidationReport check(String script) {
        return validator.validate(script, Map.of(), CallerCatalog.empty());
    }

    @Test
    void aCorrectScriptIsValidWithNothingToSay() {
        ValidationReport report = check(ORDERS + "query { SELECT amount > 100 (Orders) };");

        assertThat(report.valid()).isTrue();
        assertThat(report.findings()).isEmpty();
        assertThat(report.render()).isEqualTo("Valid.");
    }

    @Test
    void aSyntaxErrorIsPlacedAtItsLineAndColumn() {
        ValidationReport report = check(ORDERS + "query { SELECT amount <> 100 (Orders) };");

        assertThat(report.valid()).isFalse();
        Finding error = report.findings().getFirst();
        assertThat(error.isError()).isTrue();
        assertThat(error.file()).isNull();
        assertThat(error.line()).isEqualTo(7);
        assertThat(error.column()).isPositive();
    }

    @Test
    void anUnknownColumnIsAnError() {
        ValidationReport report = check(ORDERS + "query { PROJECT amount, totl (Orders) };");

        assertThat(report.valid()).isFalse();
        assertThat(report.render()).contains("totl");
    }

    @Test
    void anUnknownRelationIsAnError() {
        ValidationReport report = check(ORDERS + "query { SELECT amount > 1 (Ordres) };");

        assertThat(report.valid()).isFalse();
        assertThat(report.render()).contains("Ordres");
    }

    @Test
    void aDescribedDatabaseTableIsCheckedAgainstItsColumns() {
        String script = """
                connection wh from jdbc { url: "jdbc:postgresql://db.invalid:5432/wh" };
                query { PROJECT order_id, amount (wh.orders) };
                """;
        CallerCatalog catalog = CallerCatalog.of(Map.of("wh",
                Map.of("orders", ordered("order_id", "NUMBER", "amount", "NUMBER"))));

        assertThat(validator.validate(script, Map.of(), catalog).valid()).isTrue();
        assertThat(validator.validate(script.replace("amount (", "amt ("), Map.of(), catalog)
                .render()).contains("amt");
    }

    @Test
    void anUndescribedDatabaseTableIsReportedRatherThanLookedUp() {
        String script = """
                connection wh from jdbc { url: "jdbc:postgresql://db.invalid:5432/wh" };
                query { PROJECT order_id (wh.orders) };
                """;

        ValidationReport report = check(script);

        assertThat(report.valid()).isFalse();
        assertThat(report.render()).contains("orders");
    }

    @Test
    void anImportIsServedFromTheFilesSent() {
        String script = """
                import "lib/orders.relix";
                query { SELECT amount > 100 (Orders) };
                """;

        ValidationReport report = validator.validate(script,
                Map.of("lib/orders.relix", ORDERS), CallerCatalog.empty());

        assertThat(report.render()).isEqualTo("Valid.");
    }

    @Test
    void anImportThatWasNotSentIsAnError() {
        ValidationReport report = check("import \"lib/orders.relix\";\nquery { Orders };");

        assertThat(report.valid()).isFalse();
        assertThat(report.render()).contains("lib/orders.relix");
    }

    @Test
    void anErrorInAnImportedFileNamesThatFile() {
        String script = """
                import "lib/orders.relix";
                query { Orders };
                """;

        ValidationReport report = validator.validate(script,
                Map.of("lib/orders.relix", "Orders := { PROJECT x (Nowhere) };"),
                CallerCatalog.empty());

        assertThat(report.findings()).singleElement().satisfies(f -> {
            assertThat(f.file()).isEqualTo("lib/orders.relix");
            assertThat(f.line()).isEqualTo(1);
            assertThat(f.message()).contains("Nowhere");
        });
    }

    @Test
    void aSyntaxErrorInAnImportedFileNamesThatFile() {
        String script = """
                import "lib/orders.relix";
                query { Orders };
                """;

        ValidationReport report = validator.validate(script,
                Map.of("lib/orders.relix", "Orders := { SELECT a <> 1 (X) };"),
                CallerCatalog.empty());

        assertThat(report.findings()).singleElement().satisfies(f -> {
            assertThat(f.isError()).isTrue();
            assertThat(f.file()).isEqualTo("lib/orders.relix");
            assertThat(f.line()).isEqualTo(1);
        });
    }

    @Test
    void aSourceWithADeclaredSchemaIsCheckedWithoutItsFile() {
        String script = """
                source Customers from csv("/no/such/dir/customers.csv") {
                    header: true,
                    schema: { customer_id: NUMBER, name: STRING }
                };
                query { PROJECT name (Customers) };
                """;

        assertThat(check(script).render()).isEqualTo("Valid.");
        assertThat(check(script.replace("PROJECT name", "PROJECT nme")).valid()).isFalse();
    }

    @Test
    void anOpenSourceIsValidButWarnedAbout() {
        String script = """
                source Events from json("events.json");
                query { PROJECT kind (Events) };
                """;

        ValidationReport report = check(script);

        assertThat(report.valid()).isTrue();
        assertThat(report.findings()).singleElement()
                .satisfies(f -> {
                    assertThat(f.severity()).isEqualTo(Finding.WARNING);
                    assertThat(f.message()).contains("Events").contains("cannot declare");
                    assertThat(f.line()).isEqualTo(1);
                });
    }

    @Test
    void anHttpSourceWithoutASchemaIsWarnedAboutAndOneWithASchemaIsNot() {
        String open = """
                source Weather from http { url: "https://api.example.invalid/weather" };
                query { PROJECT temp (Weather) };
                """;
        String typed = """
                source Weather from http {
                    url: "https://api.example.invalid/weather",
                    schema: { temp: NUMBER at "$.main.temp" }
                };
                query { PROJECT temp (Weather) };
                """;

        assertThat(check(open).findings()).singleElement()
                .satisfies(f -> assertThat(f.message()).contains("schema: block"));
        assertThat(check(typed).render()).isEqualTo("Valid.");
    }

    @Test
    void warningsFollowErrors() {
        String script = """
                source Events from json("events.json");
                query { PROJECT kind (Nowhere) };
                """;

        ValidationReport report = check(script);

        assertThat(report.findings()).extracting(Finding::severity).containsOnly(Finding.ERROR);
    }

    private static Map<String, String> ordered(String... pairs) {
        Map<String, String> map = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            map.put(pairs[i], pairs[i + 1]);
        }
        return map;
    }
}
