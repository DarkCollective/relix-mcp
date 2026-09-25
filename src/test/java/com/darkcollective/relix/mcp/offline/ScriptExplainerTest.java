package com.darkcollective.relix.mcp.offline;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ScriptExplainerTest {

    private final ScriptExplainer explainer = new ScriptExplainer();

    private static final String WAREHOUSE = """
            Spend := { GROUP city, SUM(amount) -> total (SELECT amount > 100 (wh.orders JOIN wh.customers)) };
            query { SORT total DESC (Spend) };
            """;

    private static CallerCatalog warehouse() {
        Map<String, String> orders = new LinkedHashMap<>();
        orders.put("order_id", "NUMBER");
        orders.put("customer_id", "NUMBER");
        orders.put("amount", "NUMBER");
        Map<String, String> customers = new LinkedHashMap<>();
        customers.put("customer_id", "NUMBER");
        customers.put("name", "STRING");
        customers.put("city", "STRING");
        return CallerCatalog.of(Map.of("wh", Map.of("orders", orders, "customers", customers)));
    }

    @Test
    void aQueryOverInlineDataShowsItsRewritesAndPlan() {
        ExplainReport report = explainer.explain("""
                Orders := [
                | order_id | customer_id | amount |
                |----------|-------------|--------|
                | 1        | 1           | 120    |
                ];
                Big := { SELECT amount > 100 (Orders) };
                query { PROJECT order_id (SELECT customer_id = 1 (Big)) };
                """, Map.of(), CallerCatalog.empty());

        QueryPlan plan = report.queries().getFirst();
        assertThat(plan.written()).contains("Big");
        assertThat(plan.rewrites()).anySatisfy(r -> assertThat(r).startsWith("INLINE-001: "));
        assertThat(plan.optimised()).doesNotContain("Big").contains("Orders");
        assertThat(plan.plan()).contains("Scan Orders");
        assertThat(report.render()).contains("As written:", "Rewrites:", "Optimised:", "Plan:");
    }

    @Test
    void aDatabaseQueryShowsThePostgresSqlItWouldSend() {
        ExplainReport report = explainer.explain(
                "connection wh from jdbc { url: \"jdbc:postgresql://db.invalid:5432/wh\" };\n" + WAREHOUSE,
                Map.of(), warehouse());

        assertThat(report.valid()).isTrue();
        assertThat(report.queries().getFirst().plan())
                .contains("PushedScan")
                .contains("SELECT \"customers\".\"city\", SUM(\"orders\".\"amount\")")
                .contains("GROUP BY")
                .contains("NULLS LAST");
    }

    @Test
    void theDialectFollowsTheConnection() {
        ExplainReport mysql = explainer.explain(
                "connection wh from jdbc { url: \"jdbc:mysql://db.invalid:3306/wh\" };\n" + WAREHOUSE,
                Map.of(), warehouse());

        assertThat(mysql.queries().getFirst().plan()).contains("PushedScan").contains("`orders`");
    }

    @Test
    void anInvalidScriptPlansNothingAndSaysWhy() {
        ExplainReport report = explainer.explain("query { PROJECT x (Nowhere) };",
                Map.of(), CallerCatalog.empty());

        assertThat(report.valid()).isFalse();
        assertThat(report.queries()).isEmpty();
        assertThat(report.render()).startsWith("Invalid, so nothing was planned:").contains("Nowhere");
    }

    @Test
    void aScriptWithNoQuerySaysSo() {
        assertThat(explainer.explain("X := [\n| a |\n|---|\n| 1 |\n];", Map.of(), CallerCatalog.empty())
                .render()).contains("no query statement");
    }

    @Test
    void eachQueryIsPlannedInSourceOrderUnderItsName() {
        ExplainReport report = explainer.explain("""
                X := [
                | a |
                |---|
                | 1 |
                ];
                query X;
                query { SELECT a > 0 (X) };
                """, Map.of(), CallerCatalog.empty());

        assertThat(report.queries()).extracting(QueryPlan::name).containsExactly("X", null);
        assertThat(report.render()).contains("Query 1 (X):").contains("Query 2:")
                .contains("(none: the optimiser left it as written)");
    }
}
