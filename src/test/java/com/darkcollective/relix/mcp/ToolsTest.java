package com.darkcollective.relix.mcp;

import com.darkcollective.relix.docs.ReferencePage;
import com.darkcollective.relix.docs.RelixDocs;
import com.darkcollective.relix.mcp.offline.CallerCatalog;
import com.darkcollective.relix.mcp.offline.ScriptValidator;
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

class ToolsTest {

    private final Tools tools = new Tools();

    @Test
    void everyExampleInThePrimerIsValid() {
        List<String> examples = examples(Tools.PRIMER);

        assertThat(examples).hasSizeGreaterThanOrEqualTo(2);
        for (String example : examples) {
            assertThat(new ScriptValidator().validate(example, Map.of(), CallerCatalog.empty())
                    .render()).as(example).isEqualTo("Valid.");
        }
    }

    @Test
    void everyJoinThePrimerNamesValidatesAsWrittenThere() {
        String tables = """
                A := [
                | id | tag |
                |----|-----|
                | 1  | x   |
                ];
                B := [
                | a_id | tag |
                |------|-----|
                | 1    | x   |
                ];
                Customers := [
                | id | name |
                |----|------|
                | 1  | Ann  |
                ];
                Orders := [
                | order_id | customer_id |
                |----------|-------------|
                | 1        | 1           |
                ];
                """;
        String conditional = "Customers ANTI Customers.id = Orders.customer_id Orders";
        List<String> forms = new ArrayList<>(List.of("A JOIN B", "A >< A.id = B.a_id B", conditional));
        assertThat(Tools.PRIMER).contains(forms);
        // The primer says the outer and semi joins take their condition as ANTI does.
        for (String keyword : List.of("LJOIN", "RJOIN", "FJOIN", "SEMI")) {
            assertThat(Tools.PRIMER).contains(keyword);
            forms.add(conditional.replace(" ANTI ", " " + keyword + " "));
        }

        for (String form : forms) {
            // A JOIN B needs a column the two share; a join on a condition, given one, is
            // warned that the right input's copy is renamed, so it is checked without it.
            String over = form.equals("A JOIN B") ? tables : tables.replace(
                    "| a_id | tag |\n|------|-----|", "| a_id | b_tag |\n|------|-------|");
            assertThat(new ScriptValidator().validate(over + "query { " + form + " };",
                    Map.of(), CallerCatalog.empty()).render()).as(form).isEqualTo("Valid.");
        }
    }

    /**
     * The primer's TOP line, held to the engine: the form as written runs, and a tie for
     * first fills both places, with nobody second. Three runs of a race task chose "the two
     * best times" instead, keeping a third runner, every time without WITH TIES.
     */
    @Test
    void thePrimersTopWithTiesFillsBothPlacesWithATieForFirst() {
        String form = "TOP 2 WITH TIES t PER g (R)";
        assertThat(Tools.PRIMER).contains(form);
        String script = """
                R := [
                | g | name | t    |
                |---|------|------|
                | a | Hal  | 1700 |
                | a | Ida  | 1650 |
                | a | Jo   | 1650 |
                | b | Ana  | 1480 |
                | b | Ben  | 1490 |
                | b | Cleo | 1490 |
                | b | Dev  | 1530 |
                ];
                query { SORT g, name (PROJECT g, name (%s)) };
                """.formatted(form);

        String ran = text(tools.run(call("relix_run", Map.of("script", script))));

        assertThat(ran).contains("| a | Ida |").contains("| a | Jo |").doesNotContain("Hal")
                .contains("| b | Ana |").contains("| b | Ben |").contains("| b | Cleo |")
                .doesNotContain("Dev").contains("(5 rows");
    }

    /**
     * The primer's SELECT, PROJECT and GROUP lines, held to the engine. A model expecting SQL
     * reads a column list beside SELECT as SELECT's, so each sits on its own line, and the
     * claim that an aggregate works only inside GROUP is a claim about what the engine
     * refuses.
     */
    @Test
    void theSelectProjectAndGroupLinesSayWhatTheEngineDoes() {
        String table = """
                R := [
                | k | x | a | b |
                |---|---|---|---|
                | p | 1 | 4 | 2 |
                | p | 2 | 3 | 1 |
                ];
                """;
        List<String> forms = List.of("SELECT x > 1 (R)", "PROJECT a, b * 2 -> c (R)",
                "GROUP k, SUM(x) -> total, COUNT(*) -> n (R)", "GROUP SUM(x) -> total (R)");
        assertThat(Tools.PRIMER).contains("It is a filter, not SQL's SELECT.")
                .contains("this is SQL's SELECT list").contains(forms.subList(1, 4));
        assertThat(Tools.PRIMER.lines()).anySatisfy(line -> assertThat(line).startsWith("SELECT cond (R)"))
                .anySatisfy(line -> assertThat(line).startsWith("PROJECT a, b * 2 -> c (R)"))
                .anySatisfy(line -> assertThat(line).startsWith("GROUP k, SUM(x)"));

        for (String form : forms) {
            assertThat(new ScriptValidator().validate(table + "query { " + form + " };",
                    Map.of(), CallerCatalog.empty()).render()).as(form).isEqualTo("Valid.");
        }

        CallToolResult whole = tools.run(call("relix_run",
                Map.of("script", table + "query { GROUP SUM(x) -> total (R) };")));
        assertThat(text(whole)).contains("| 3 |").contains("(1 row");

        for (String aggregate : List.of("SUM(x)", "COUNT(*)", "AVG(x)", "MIN(x)", "MAX(x)")) {
            assertThat(new ScriptValidator().validate(table + "query { GROUP " + aggregate + " -> v (R) };",
                    Map.of(), CallerCatalog.empty()).render()).as(aggregate).isEqualTo("Valid.");
            assertThat(new ScriptValidator().validate(table + "query { PROJECT " + aggregate + " -> v (R) };",
                    Map.of(), CallerCatalog.empty()).render()).as(aggregate).isNotEqualTo("Valid.");
        }
    }

    @Test
    void theToolsAreNamedAfterTheLanguage() {
        assertThat(tools.all()).extracting(t -> t.tool().name())
                .containsExactly("relix_validate", "relix_explain", "relix_run", "relix_submit", "relix_learn");
        assertThat(tools.all()).allSatisfy(t ->
                assertThat(t.tool().annotations().readOnlyHint()).isTrue());
    }

    @Test
    void validateAnswersInTextAndAsData() {
        CallToolResult result = tools.validate(call("relix_validate",
                Map.of("script", "query { SELECT a > 1 (Nowhere) };")));

        assertThat(result.isError()).isNotEqualTo(Boolean.TRUE);
        assertThat(text(result)).startsWith("Invalid: 1 error.").contains("Nowhere")
                .endsWith(Tools.REVALIDATE);
        assertThat(result.structuredContent()).isInstanceOf(Map.class);
        Map<?, ?> data = (Map<?, ?>) result.structuredContent();
        assertThat(data.get("valid")).isEqualTo(false);
        assertThat((List<?>) data.get("findings")).singleElement()
                .satisfies(f -> assertThat(((Map<?, ?>) f).keySet().stream().map(String::valueOf))
                        .containsExactly("severity", "message", "file", "line", "column"));
    }

    @Test
    void validateUsesTheFilesAndCatalogSent() {
        Map<String, Object> args = new HashMap<>();
        args.put("script", """
                import "lib.relix";
                connection wh from jdbc { url: "jdbc:postgresql://db.invalid/wh" };
                query { PROJECT id (wh.orders) JOIN Ids };
                """);
        args.put("files", Map.of("lib.relix", "Ids := [\n| id |\n|----|\n| 1 |\n];"));
        args.put("catalog", Map.of("wh", Map.of("orders", Map.of("id", "NUMBER"))));

        assertThat(text(tools.validate(call("relix_validate", args)))).isEqualTo("Valid.");
    }

    @Test
    void runReturnsTheRowsInTextAndAsData() {
        CallToolResult result = tools.run(call("relix_run", Map.of("script", examples(Tools.PRIMER).getFirst())));

        assertThat(text(result)).startsWith("Query 1:").contains("| 1 | 120 |")
                .contains("(1 row, in no guaranteed order)");
        Map<?, ?> data = (Map<?, ?>) result.structuredContent();
        assertThat(data.get("valid")).isEqualTo(true);
        assertThat((List<?>) data.get("results")).singleElement().satisfies(r -> assertThat(
                ((Map<?, ?>) r).keySet().stream().map(String::valueOf))
                .containsExactly("name", "columns", "types", "ordering", "rows", "truncated", "failure"));
    }

    /**
     * A client that reads JSON numbers as doubles, as JavaScript's {@code JSON.parse} does,
     * reads each number in a run's rows as the engine returned it. One a double cannot hold
     * is sent as its exact text, so it is not rounded to a neighbour; one it can is a number.
     */
    @Test
    void runSendsEveryNumberAsADoubleReaderReadsIt() throws Exception {
        CallToolResult result = tools.run(call("relix_run", Map.of("script", """
                T := [
                | n                   |
                |---------------------|
                | 9007199254740993    |
                | 9223372036854775807 |
                | 9007199254740992    |
                | 1.5                 |
                | 0.1                 |
                ];
                query { SORT n ASC (T) };
                """)));

        Map<?, ?> data = (Map<?, ?>) McpJsonDefaults.getMapper()
                .readValue(McpJsonDefaults.getMapper().writeValueAsString(result.structuredContent()), Map.class);
        Map<?, ?> query = (Map<?, ?>) ((List<?>) data.get("results")).getFirst();
        assertThat(query.get("types")).isEqualTo(List.of("NUMBER"));
        assertThat(((List<?>) query.get("rows")).stream()
                .map(row -> ((List<?>) row).getFirst())
                .map(v -> v instanceof Number n ? (Object) Double.parseDouble(n.toString()) : v))
                .containsExactly(0.1, 1.5, 9007199254740992.0, "9007199254740993", "9223372036854775807");
    }

    /**
     * Every tool a description, an answer or the server's instructions tells a model to call
     * is one the server lists, and none is named the old way, without the language. A model
     * that calls a name the server does not have gets an error, not the tool.
     */
    @Test
    void everyToolTheTextNamesIsOneTheServerLists() {
        Set<String> names = new HashSet<>();
        StringBuilder text = new StringBuilder(Main.INSTRUCTIONS);
        for (Tools.Definition definition : tools.all()) {
            names.add(definition.tool().name());
            text.append('\n').append(definition.tool().description());
        }
        text.append(Tools.REVALIDATE).append(text(tools.run(call("relix_run",
                Map.of("script", ForeignCodeTest.KNAPSACK)))));

        Matcher named = Pattern.compile("'(relix_\\w+)'").matcher(text);
        int found = 0;
        while (named.find()) {
            assertThat(names).contains(named.group(1));
            found++;
        }
        assertThat(found).isGreaterThan(10);
        assertThat(text.toString()).doesNotContainPattern("'(validate|explain|run|submit|learn)'");
    }

    @Test
    void runAndSubmitSayFirstThatTheyRunRelixOnly() {
        assertThat(tools.runTool().tool().description()).startsWith(Tools.RELIX_ONLY);
        assertThat(tools.submitTool().tool().description()).startsWith(Tools.RELIX_ONLY);
    }

    @Test
    void codeInAnotherLanguageIsAnsweredWithWhereToLook() {
        String pointer = ForeignCode.pointer(ForeignCodeTest.KNAPSACK).orElseThrow();
        Map<String, Object> args = Map.of("script", ForeignCodeTest.KNAPSACK);

        assertThat(text(tools.run(call("relix_run", args)))).startsWith(pointer)
                .contains("Invalid, so nothing ran:\nscript:1:");
        assertThat(text(tools.submit(call("relix_submit", args)))).startsWith(pointer)
                .contains("Not submitted");
        assertThat(text(tools.validate(call("relix_validate", args)))).startsWith(pointer)
                .endsWith(Tools.REVALIDATE);
        assertThat(text(tools.explain(call("relix_explain", args)))).startsWith(pointer);
    }

    @Test
    void anInvalidRelixScriptGetsItsErrorsAlone() {
        String script = "query { SELECT amount >> 100 (Orders) };";
        assertThat(text(tools.run(call("relix_run", Map.of("script", script)))))
                .doesNotContain("not a code runner");
    }

    @Test
    void runSaysThereIsNoNeedToValidateFirst() {
        assertThat(tools.runTool().tool().description())
                .contains("There is no need to call 'relix_validate' before 'relix_run'")
                .contains("in no guaranteed order").contains("'relix_submit'");
    }

    @Test
    void submitReturnsTheScriptAndTheEnginesRowsTogether() {
        String script = examples(Tools.PRIMER).getFirst();
        CallToolResult result = tools.submit(call("relix_submit", Map.of("script", script)));

        assertThat(result.isError()).isNotEqualTo(Boolean.TRUE);
        // The same rows relix_run shows, without its reminder to submit them.
        String ran = text(tools.run(call("relix_run", Map.of("script", script))))
                .replace("\n\n" + Tools.SUBMIT_REMINDER, "");
        assertThat(text(result)).isEqualTo(Tools.SUBMITTED + "\n\n```relix\n" + script.strip()
                + "\n```\n\n" + ran);
        Map<?, ?> data = (Map<?, ?>) result.structuredContent();
        assertThat(data.get("submitted")).isEqualTo(true);
        assertThat(data.get("script")).isEqualTo(script);
        assertThat((List<?>) data.get("results")).hasSize(1);
    }

    @Test
    void aScriptWithBackticksIsFencedLongerThanThem() {
        String script = "-- ``` is not a fence here\nquery { PROJECT 1 -> one (UNIT) };";

        assertThat(text(tools.submit(call("relix_submit", Map.of("script", script)))))
                .contains("\n````relix\n" + script + "\n````\n");
    }

    @Test
    void anInvalidScriptIsNotSubmitted() {
        CallToolResult result = tools.submit(call("relix_submit",
                Map.of("script", "query { SELECT a > 1 (Nowhere) };")));

        assertThat(text(result)).startsWith("Not submitted: fix these").contains("Nowhere")
                .doesNotContain(Tools.SUBMITTED);
        assertThat(((Map<?, ?>) result.structuredContent()).get("submitted")).isEqualTo(false);
        assertThat(tools.submit(call("relix_submit", Map.of())).isError()).isTrue();
    }

    @Test
    void theInstructionsSayToFinishWithSubmit() {
        assertThat(Main.INSTRUCTIONS).contains("Finish by calling 'relix_submit'")
                .contains("output unchanged");
    }

    @Test
    void runRefusesExternalDataAndAMissingScript() {
        assertThat(text(tools.run(call("relix_run", Map.of("script",
                "source C from csv(\"c.csv\") { header: true, schema: { id: NUMBER } };\nquery { C };")))))
                .contains("does not permit");
        assertThat(tools.run(call("relix_run", Map.of())).isError()).isTrue();
    }

    @Test
    void theCatalogQueryRunsInsideTheSandboxAndDescribesTheScriptsTables() {
        assertThat(tools.runTool().tool().description()).contains(Tools.CATALOG_QUERY);
        String orders = examples(Tools.PRIMER).getFirst().lines()
                .takeWhile(line -> !line.startsWith("Big"))
                .reduce("", (script, line) -> script + line + "\n");

        CallToolResult result = tools.run(call("relix_run", Map.of("script", orders + Tools.CATALOG_QUERY)));

        Map<?, ?> data = (Map<?, ?>) result.structuredContent();
        assertThat(data.get("valid")).isEqualTo(true);
        Map<?, ?> columns = (Map<?, ?>) ((List<?>) data.get("results")).getFirst();
        assertThat(columns.get("failure")).isNull();
        assertThat((List<?>) columns.get("rows")).extracting(row -> ((List<?>) row).get(0) + "." + ((List<?>) row).get(1))
                .containsExactly("Orders.order_id", "Orders.customer_id", "Orders.amount");
    }

    @Test
    void explainShowsTheSqlForTheCallersDatabaseInTextAndAsData() {
        Map<String, Object> args = new HashMap<>();
        args.put("script", """
                connection wh from jdbc { url: "jdbc:postgresql://db.invalid/wh" };
                query { SELECT amount > 100 (wh.orders) };
                """);
        args.put("catalog", Map.of("wh", Map.of("orders", Map.of("amount", "NUMBER"))));

        CallToolResult result = tools.explain(call("relix_explain", args));

        assertThat(text(result)).contains("Plan:").contains("PushedScan").contains("WHERE");
        Map<?, ?> data = (Map<?, ?>) result.structuredContent();
        assertThat((List<?>) data.get("queries")).singleElement().satisfies(q -> assertThat(
                ((Map<?, ?>) q).keySet().stream().map(String::valueOf))
                .containsExactly("name", "written", "rewrites", "optimised", "plan", "failure"));
    }

    @Test
    void explainReadsItsArgumentsAsValidateDoes() {
        assertThat(tools.explain(call("relix_explain", Map.of())).isError()).isTrue();
        assertThat(text(tools.explain(call("relix_explain", Map.of())))).contains("to explain");
        assertThat(tools.explain(call("relix_explain", Map.of("script", "", "catalog", "x"))).isError())
                .isTrue();
    }

    @Test
    void aMissingScriptIsAToolError() {
        CallToolResult result = tools.validate(call("relix_validate", Map.of()));

        assertThat(result.isError()).isTrue();
        assertThat(text(result)).contains("'script' is required");
    }

    @Test
    void aCatalogNamingAnUnknownTypeIsAToolErrorListingTheTypes() {
        CallToolResult result = tools.validate(call("relix_validate", Map.of(
                "script", "query { X };",
                "catalog", Map.of("wh", Map.of("t", Map.of("c", "INTEGER"))))));

        assertThat(result.isError()).isTrue();
        assertThat(text(result)).contains("wh.t.c").contains("INTEGER").contains("NUMBER");
    }

    @Test
    void malformedFilesAndCatalogAreToolErrors() {
        assertThat(tools.validate(call("relix_validate", Map.of("script", "", "files", "x"))).isError())
                .isTrue();
        assertThat(tools.validate(call("relix_validate", Map.of("script", "",
                "files", Map.of("a.relix", 1)))).isError()).isTrue();
        assertThat(tools.validate(call("relix_validate", Map.of("script", "", "catalog", "x")))
                .isError()).isTrue();
        assertThat(tools.validate(call("relix_validate", Map.of("script", "",
                "catalog", Map.of("wh", "x")))).isError()).isTrue();
        assertThat(tools.validate(call("relix_validate", Map.of("script", "",
                "catalog", Map.of("wh", Map.of("t", Map.of()))))).isError()).isTrue();
        assertThat(tools.validate(call("relix_validate", Map.of("script", "",
                "catalog", Map.of("wh", Map.of("t", Map.of("c", 3)))))).isError()).isTrue();
    }

    @Test
    void learnWithNoTopicListsThePages() {
        String contents = text(tools.learn(call("relix_learn", Map.of())));

        assertThat(contents).contains("Selection").contains("operators/select.md");
    }

    @Test
    void learnFindsAPageByGlyphKeywordOrPath() {
        String byGlyph = text(tools.learn(call("relix_learn", Map.of("topic", "σ"))));

        assertThat(byGlyph).contains("Selection");
        assertThat(text(tools.learn(call("relix_learn", Map.of("topic", "SELECT"))))).isEqualTo(byGlyph);
        assertThat(text(tools.learn(call("relix_learn", Map.of("topic", "operators/select.md")))))
                .isEqualTo(byGlyph);
    }

    @Test
    void aBriefPageIsItsSyntaxAndOneExample() {
        String full = text(tools.learn(call("relix_learn", Map.of("topic", "σ"))));
        String brief = text(tools.learn(call("relix_learn", Map.of("topic", "σ", "detail", "brief"))));

        assertThat(brief).startsWith("# Name:").contains("# Syntax:").contains("# Example:")
                .contains("σ age >= 18 (Users)").doesNotContain("# Description:")
                .doesNotContain("Customers in a specific city")
                .endsWith("without detail 'brief' for the whole page.)");
        assertThat(brief.length()).isLessThan(full.length() / 4);
        assertThat(text(tools.learn(call("relix_learn", Map.of("topic", "σ", "detail", "full")))))
                .isEqualTo(full);
    }

    @Test
    void aBriefPageKeepsTheSqlNamesItAnswersTo() {
        // gpt-oss asked for 'lower', got a brief page titled LCase, and concluded LOWER
        // did not exist: the section saying it does had been cut.
        String brief = text(tools.learn(call("relix_learn", Map.of("topic", "LOWER", "detail", "brief"))));

        assertThat(brief).startsWith("# Name: LCase").contains("# SQL names:")
                .contains("`LOWER` is the same function").doesNotContain("# Description:")
                .doesNotContain("# See Also:");
    }

    @Test
    void everyPageHasABriefForm() {
        List<String> pages = new ArrayList<>();
        RelixDocs.referencePages().forEach(p -> pages.add(p.path()));
        pages.addAll(List.of("round", "coalesce"));
        for (String page : pages) {
            String brief = text(tools.learn(call("relix_learn", Map.of("topic", page, "detail", "brief"))));
            assertThat(brief).as(page).contains("# Syntax:").contains("# Example:")
                    .doesNotContain("# Description:");
            String example = brief.substring(brief.indexOf("# Example:"));
            assertThat(example.lines()).as(page + ": the example holds code")
                    .anyMatch(l -> l.startsWith(" ") || l.startsWith("```"));
        }
    }

    @Test
    void learnFindsAFunctionPageFromItsLibrary() {
        String page = text(tools.learn(call("relix_learn", Map.of("topic", "round"))));

        assertThat(page).doesNotContain("No reference page").contains("Round");
        assertThat(text(tools.learn(call("relix_learn", Map.of())))).contains("Round");
    }

    @Test
    void aMissPointsAtTheSpellingsPage() {
        assertThat(text(tools.learn(call("relix_learn", Map.of("topic", "zzzzqqq")))))
                .contains("spellings");
    }

    @Test
    void learnFindsAnOperatorByWhatItDoes() {
        assertThat(firstHit("pairwise test combinations")).isEqualTo("Cover");
        assertThat(firstHit("group events into sessions by inactivity gap")).isEqualTo("Sessionize");
        assertThat(firstHit("latest price as of each trade")).isEqualTo("AS-OF join");
        assertThat(firstHit("shortest path between cities")).isEqualTo("Path");
    }

    @Test
    void sqlsConditionalKeywordsFindTheConditionalFunctions() {
        // Issue 44: relix-docs keys Switch by case and case when, and IIf by if (relix-core
        // #189); looking a topic up through its ReferenceLookup reaches them directly.
        String learnt = text(tools.learn(call("relix_learn", Map.of("topic", "CASE"))));
        assertThat(learnt).startsWith("# Name: Switch");
        assertThat(text(tools.learn(call("relix_learn", Map.of("topic", "case when")))))
                .isEqualTo(learnt);
        assertThat(text(tools.learn(call("relix_learn", Map.of("topic", "IF")))))
                .startsWith("# Name: IIf");
    }

    @Test
    void aFunctionIsFoundByItsOwnSummary() {
        // Issue 42: a function page's summary was not weighed as a heading, so "conditional
        // value", IIf's own subtitle, ranked FILTER first.
        assertThat(firstHit("conditional value")).isEqualTo("IIf");
        assertThat(firstHit("inline if")).isEqualTo("IIf");
        assertThat(firstHit("multi-branch conditional")).isEqualTo("Switch");
    }

    @Test
    void anAsciiKeywordLandsOnItsPageNotTheQuantifier() {
        // "anti" is inside "quantifier"; asking for the ASCII keyword ANTI must land on
        // the anti join. The engine registers each operator's ASCII spelling as a page
        // key, so ANTI now resolves to the page directly rather than through search.
        String answer = text(tools.learn(call("relix_learn", Map.of("topic", "ANTI"))));
        assertThat(answer).contains("Anti Join").contains("▷")
                .doesNotContain("Universal Quantification");
    }

    @Test
    void aSearchShowsTheSummaryAndTheLineThatMatched() {
        String answer = text(tools.learn(call("relix_learn", Map.of("topic", "pairwise test combinations"))));

        assertThat(answer).contains("- Cover [advanced/cover.md]: Combinatorial")
                .contains("  > ").contains("pairwise");
    }

    @Test
    void aTopicNoPageMentionsSaysSo() {
        assertThat(text(tools.learn(call("relix_learn", Map.of("topic", "zzzzqqq")))))
                .contains("No page mentions it").contains("no topic");
    }

    @Test
    void aSuccessfulRunEndsBySayingToSubmitIt() {
        // Issue 41: a model ran the right script, got the right rows, and wrote them in its
        // reply without calling relix_submit, so its answer never became one.
        String table = "T := [\n| id | n |\n|----|---|\n| 1  | 0 |\n];\n";

        assertThat(text(tools.run(call("relix_run", Map.of("script", table + "query { T };")))))
                .endsWith("\n\n" + Tools.SUBMIT_REMINDER);
        assertThat(text(tools.run(call("relix_run", Map.of("script", table + "query { Nowhere };")))))
                .doesNotContain(Tools.SUBMIT_REMINDER);
        assertThat(text(tools.run(call("relix_run", Map.of("script", table + "Kept := { T };")))))
                .contains("no query statement").doesNotContain(Tools.SUBMIT_REMINDER);
        assertThat(text(tools.run(call("relix_run",
                Map.of("script", table + "query { PROJECT id / n -> r (T) };")))))
                .contains("Failed:").doesNotContain(Tools.SUBMIT_REMINDER);
        assertThat(text(tools.submit(call("relix_submit", Map.of("script", table + "query { T };")))))
                .doesNotContain(Tools.SUBMIT_REMINDER);
    }

    /**
     * A run with a warning ends by pointing at the warning, not by saying to submit: a model
     * submitted rows from a join that had renamed the column it projected, past the warning
     * that said so. A submission with a warning stands, and says it has one.
     */
    @Test
    void aRunWithWarningsPointsAtThemBeforeSubmitting() {
        String tables = "R := [\n| room_id | name |\n|---|---|\n| 1 | Garage |\n];\n"
                + "D := [\n| id | room_id | name |\n|---|---|---|\n| 9 | 1 | Garage Device 1 |\n];\n";
        String script = tables + "V := { R >< R.room_id = D.room_id D };\nquery { PROJECT id, name (V) };";

        String ran = text(tools.run(call("relix_run", Map.of("script", script))));
        assertThat(ran).contains("name_r").endsWith("\n\n" + Tools.warningsFirst(1))
                .doesNotContain(Tools.SUBMIT_REMINDER);

        CallToolResult submitted = tools.submit(call("relix_submit", Map.of("script", script)));
        assertThat(((Map<?, ?>) submitted.structuredContent()).get("submitted")).isEqualTo(true);
        assertThat(text(submitted)).endsWith("\n\n" + Tools.submittedWithWarnings(1));
    }

    /** A script whose eighth line uses SQL's SELECT list, below an inline table. */
    private static final String SQL_SELECT_ON_LINE_8 = """
            T := [
            | id | name |
            |----|------|
            | 1  | Ann  |
            ];
            Kept := { SELECT id > 0 (T) };
            -- the next line is the mistake
            Named := { SELECT id, name (Kept) };
            query Named;
            """;

    /** The line and caret a finding at line 8, column 21 (the comma) is shown with. */
    private static final String EXCERPT = "\n  Named := { SELECT id, name (Kept) };\n"
            + "  " + " ".repeat(20) + "^";

    @Test
    void aFindingShowsTheLineItPointsAtWithACaret() {
        // gpt-oss read 'script:31:31', could not tell which of its SELECTs was on line 31,
        // and rewrote a different one four times (issue 38).
        String validated = text(tools.validate(call("relix_validate",
                Map.of("script", SQL_SELECT_ON_LINE_8))));

        assertThat(validated).contains("script:8:21: error:").contains(EXCERPT);
        assertThat(text(tools.run(call("relix_run", Map.of("script", SQL_SELECT_ON_LINE_8)))))
                .contains(EXCERPT);
        assertThat(text(tools.explain(call("relix_explain", Map.of("script", SQL_SELECT_ON_LINE_8)))))
                .contains(EXCERPT);
        assertThat(text(tools.submit(call("relix_submit", Map.of("script", SQL_SELECT_ON_LINE_8)))))
                .contains(EXCERPT);
    }

    @Test
    void aFindingInAnImportedFileShowsThatFilesLine() {
        Map<String, Object> args = new HashMap<>();
        args.put("script", "import \"lib/orders.relix\";\nquery { Orders };");
        args.put("files", Map.of("lib/orders.relix", "-- orders\nOrders := { PROJECT x (Nowhere) };"));

        String validated = text(tools.validate(call("relix_validate", args)));

        assertThat(validated).contains("lib/orders.relix:2:").contains("Nowhere")
                .contains("\n  Orders := { PROJECT x (Nowhere) };\n")
                .doesNotContain("  import \"lib/orders.relix\";");
    }

    @Test
    void theStructuredFindingIsUnchangedByTheExcerpt() {
        CallToolResult result = tools.validate(call("relix_validate",
                Map.of("script", SQL_SELECT_ON_LINE_8)));

        assertThat(result.structuredContent().toString()).contains("line=8").contains("column=21")
                .doesNotContain("^");
    }

    @Test
    void aRequestNamingAFunctionCategoryListsTheWholeCategory() {
        // Issue 37: "string" weighs every string function alike, and a ranking cut to
        // eight kept Len and UCase but not Replace or SUBSTRING.
        String answer = text(tools.learn(call("relix_learn", Map.of("topic", "string manipulation"))));

        assertThat(answer).contains("The string functions").contains("Replace")
                .contains("SUBSTRING").contains("Left").contains("Mid");
        assertThat(text(tools.learn(call("relix_learn", Map.of("topic", "date arithmetic")))))
                .contains("The datetime functions").contains("DATEADD");
    }

    @Test
    void sqlsConversionNamesReachCast() {
        // Issue 37: a model looked conversion up as TO_NUMBER and VALUE and found nothing.
        String cast = text(tools.learn(call("relix_learn", Map.of("topic", "CAST"))));

        for (String name : List.of("TO_NUMBER", "VALUE", "CONVERT", "PARSE")) {
            assertThat(text(tools.learn(call("relix_learn", Map.of("topic", name)))))
                    .as(name).isEqualTo(cast);
        }
    }

    @Test
    void aWordThatMerelyStartsACategoryNamesNone() {
        assertThat(new Reference().categories("ma da str")).isEmpty();
        assertThat(new Reference().categories("pairwise test combinations")).isEmpty();
    }

    @Test
    void aSearchSaysHowManyMorePagesMatchThanItShows() {
        Reference.Found found = new Reference().search("text value");
        assertThat(found.matched()).isGreaterThan(Reference.SHOWN);

        String answer = text(tools.learn(call("relix_learn", Map.of("topic", "text value"))));
        assertThat(answer).contains((found.matched() - Reference.SHOWN) + " more pages mention it");
    }

    /**
     * The keywords the primer names for the operators SQL lacks, and the one family it
     * names by its page's title, record linkage having no keyword of its own. A model reads the primer's
     * list as the whole of what Relix can do, so a family missing from it is one the model
     * concludes the language cannot express (issue 26: no optimisation, so a knapsack was
     * solved in Python and its answer recited in a script).
     */
    private static final List<String> PRIMER_OPERATORS = List.of("CLOSURE", "PATH", "TRACE",
            "FIX", "ITERATE", "OPTIMIZE", "SOLVE", "COVER", "SESSIONIZE", "DOWNSAMPLE", "ASOF",
            "TOP", "SAMPLE", "CLUSTER", "record linkage");

    /** Pages in {@code advanced} that describe the engine rather than an operator. */
    private static final Set<String> NOT_OPERATORS = Set.of("Optimizer", "Pushdown");

    /**
     * Pages a keyword the primer names does not open, but whose page links them: one
     * spelling, two pages.
     */
    private static final Map<String, String> REACHED_THROUGH = Map.of("Reservoir", "SAMPLE");

    @Test
    void thePrimerNamesAnOperatorForEveryAdvancedPage() {
        Reference reference = new Reference();
        Set<String> reached = new HashSet<>();
        for (String keyword : PRIMER_OPERATORS) {
            assertThat(Tools.PRIMER).as(keyword).containsPattern("\\b" + keyword + "\\b");
            reached.add(reference.page(keyword).orElseThrow(
                    () -> new AssertionError("no page for " + keyword)));
        }

        for (ReferencePage page : reference.category("advanced")) {
            if (NOT_OPERATORS.contains(page.title())) {
                continue;
            }
            String through = REACHED_THROUGH.get(page.title());
            if (through != null) {
                String file = page.path().substring(page.path().lastIndexOf('/') + 1);
                assertThat(reference.page(through).orElseThrow())
                        .as(through + " links " + page.title()).contains("(" + file + ")");
                continue;
            }
            String markdown = RelixDocs.referencePage(page.path()).orElseThrow();
            assertThat(reached).as("the primer names no keyword reaching " + page.title()
                    + " (" + page.path() + ")").contains(markdown);
        }
    }

    @Test
    void learnsDescriptionNamesTheOperatorsSqlLacks() {
        String description = tools.learnTool().tool().description();

        assertThat(description).contains("- Cover: Combinatorial")
                .contains("- Sessionize:").contains("- Closure:");
    }

    @Test
    void theGrammarIsAPageLearnServesAndTheSiteCopiesAreLinked() {
        String grammar = text(tools.learn(call("relix_learn", Map.of("topic", Tools.GRAMMAR_PAGE))));

        assertThat(grammar).startsWith("# Name: Grammar").contains("::=");
        assertThat(tools.learnTool().tool().description()).contains(Tools.GRAMMAR_PAGE)
                .contains(Tools.LLMS_TXT).contains(Tools.GRAMMAR_TXT);
        assertThat(Main.INSTRUCTIONS).contains(Tools.GRAMMAR_PAGE)
                .contains(Tools.LLMS_TXT).contains(Tools.GRAMMAR_TXT);
    }

    @Test
    void theInstructionsAskForAFixToBeValidatedAgain() {
        assertThat(Main.INSTRUCTIONS).contains("validate it again after every change")
                .contains("the tables and columns you were given");
    }

    private String firstHit(String request) {
        return text(tools.learn(call("relix_learn", Map.of("topic", request)))).lines()
                .filter(l -> l.startsWith("- ")).findFirst()
                .map(l -> l.substring(2, l.indexOf(" [")))
                .orElse("(none)");
    }

    /** The indented blocks of the primer, each one a script on its own. */
    static List<String> examples(String primer) {
        List<String> blocks = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (String line : primer.split("\n", -1)) {
            if (line.startsWith("  ")) {
                current.append(line.substring(2)).append('\n');
            } else if (!current.isEmpty()) {
                blocks.add(current.toString());
                current.setLength(0);
            }
        }
        return blocks;
    }

    private static CallToolRequest call(String name, Map<String, Object> args) {
        return CallToolRequest.builder(name).arguments(args).build();
    }

    private static String text(CallToolResult result) {
        return ((TextContent) result.content().getFirst()).text();
    }
}
