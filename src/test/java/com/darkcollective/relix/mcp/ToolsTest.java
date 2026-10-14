package com.darkcollective.relix.mcp;

import com.darkcollective.relix.docs.ReferencePage;
import com.darkcollective.relix.docs.RelixDocs;
import com.darkcollective.relix.mcp.offline.CallerCatalog;
import com.darkcollective.relix.mcp.offline.ScriptValidator;
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
            assertThat(new ScriptValidator().validate(tables + "query { " + form + " };",
                    Map.of(), CallerCatalog.empty()).render()).as(form).isEqualTo("Valid.");
        }
    }

    @Test
    void theToolsAreValidateExplainRunSubmitAndLearn() {
        assertThat(tools.all()).extracting(t -> t.tool().name())
                .containsExactly("validate", "explain", "run", "submit", "learn");
        assertThat(tools.all()).allSatisfy(t ->
                assertThat(t.tool().annotations().readOnlyHint()).isTrue());
    }

    @Test
    void validateAnswersInTextAndAsData() {
        CallToolResult result = tools.validate(call("validate",
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

        assertThat(text(tools.validate(call("validate", args)))).isEqualTo("Valid.");
    }

    @Test
    void runReturnsTheRowsInTextAndAsData() {
        CallToolResult result = tools.run(call("run", Map.of("script", examples(Tools.PRIMER).getFirst())));

        assertThat(text(result)).startsWith("Query 1:").contains("| 1 | 120 |")
                .contains("(1 row, in no guaranteed order)");
        Map<?, ?> data = (Map<?, ?>) result.structuredContent();
        assertThat(data.get("valid")).isEqualTo(true);
        assertThat((List<?>) data.get("results")).singleElement().satisfies(r -> assertThat(
                ((Map<?, ?>) r).keySet().stream().map(String::valueOf))
                .containsExactly("name", "columns", "types", "ordering", "rows", "truncated", "failure"));
    }

    @Test
    void runSaysThereIsNoNeedToValidateFirst() {
        assertThat(tools.runTool().tool().description())
                .contains("There is no need to call 'validate' before 'run'")
                .contains("in no guaranteed order").contains("'submit'");
    }

    @Test
    void submitReturnsTheScriptAndTheEnginesRowsTogether() {
        String script = examples(Tools.PRIMER).getFirst();
        CallToolResult result = tools.submit(call("submit", Map.of("script", script)));

        assertThat(result.isError()).isNotEqualTo(Boolean.TRUE);
        assertThat(text(result)).isEqualTo(Tools.SUBMITTED + "\n\n```relix\n" + script.strip()
                + "\n```\n\n" + text(tools.run(call("run", Map.of("script", script)))));
        Map<?, ?> data = (Map<?, ?>) result.structuredContent();
        assertThat(data.get("submitted")).isEqualTo(true);
        assertThat(data.get("script")).isEqualTo(script);
        assertThat((List<?>) data.get("results")).hasSize(1);
    }

    @Test
    void aScriptWithBackticksIsFencedLongerThanThem() {
        String script = "-- ``` is not a fence here\nquery { PROJECT 1 -> one (UNIT) };";

        assertThat(text(tools.submit(call("submit", Map.of("script", script)))))
                .contains("\n````relix\n" + script + "\n````\n");
    }

    @Test
    void anInvalidScriptIsNotSubmitted() {
        CallToolResult result = tools.submit(call("submit",
                Map.of("script", "query { SELECT a > 1 (Nowhere) };")));

        assertThat(text(result)).startsWith("Not submitted: fix these").contains("Nowhere")
                .doesNotContain(Tools.SUBMITTED);
        assertThat(((Map<?, ?>) result.structuredContent()).get("submitted")).isEqualTo(false);
        assertThat(tools.submit(call("submit", Map.of())).isError()).isTrue();
    }

    @Test
    void theInstructionsSayToFinishWithSubmit() {
        assertThat(Main.INSTRUCTIONS).contains("Finish by calling 'submit'")
                .contains("output unchanged");
    }

    @Test
    void runRefusesExternalDataAndAMissingScript() {
        assertThat(text(tools.run(call("run", Map.of("script",
                "source C from csv(\"c.csv\") { header: true, schema: { id: NUMBER } };\nquery { C };")))))
                .contains("does not permit");
        assertThat(tools.run(call("run", Map.of())).isError()).isTrue();
    }

    @Test
    void theCatalogQueryRunsInsideTheSandboxAndDescribesTheScriptsTables() {
        assertThat(tools.runTool().tool().description()).contains(Tools.CATALOG_QUERY);
        String orders = examples(Tools.PRIMER).getFirst().lines()
                .takeWhile(line -> !line.startsWith("Big"))
                .reduce("", (script, line) -> script + line + "\n");

        CallToolResult result = tools.run(call("run", Map.of("script", orders + Tools.CATALOG_QUERY)));

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

        CallToolResult result = tools.explain(call("explain", args));

        assertThat(text(result)).contains("Plan:").contains("PushedScan").contains("WHERE");
        Map<?, ?> data = (Map<?, ?>) result.structuredContent();
        assertThat((List<?>) data.get("queries")).singleElement().satisfies(q -> assertThat(
                ((Map<?, ?>) q).keySet().stream().map(String::valueOf))
                .containsExactly("name", "written", "rewrites", "optimised", "plan", "failure"));
    }

    @Test
    void explainReadsItsArgumentsAsValidateDoes() {
        assertThat(tools.explain(call("explain", Map.of())).isError()).isTrue();
        assertThat(text(tools.explain(call("explain", Map.of())))).contains("to explain");
        assertThat(tools.explain(call("explain", Map.of("script", "", "catalog", "x"))).isError())
                .isTrue();
    }

    @Test
    void aMissingScriptIsAToolError() {
        CallToolResult result = tools.validate(call("validate", Map.of()));

        assertThat(result.isError()).isTrue();
        assertThat(text(result)).contains("'script' is required");
    }

    @Test
    void aCatalogNamingAnUnknownTypeIsAToolErrorListingTheTypes() {
        CallToolResult result = tools.validate(call("validate", Map.of(
                "script", "query { X };",
                "catalog", Map.of("wh", Map.of("t", Map.of("c", "INTEGER"))))));

        assertThat(result.isError()).isTrue();
        assertThat(text(result)).contains("wh.t.c").contains("INTEGER").contains("NUMBER");
    }

    @Test
    void malformedFilesAndCatalogAreToolErrors() {
        assertThat(tools.validate(call("validate", Map.of("script", "", "files", "x"))).isError())
                .isTrue();
        assertThat(tools.validate(call("validate", Map.of("script", "",
                "files", Map.of("a.relix", 1)))).isError()).isTrue();
        assertThat(tools.validate(call("validate", Map.of("script", "", "catalog", "x")))
                .isError()).isTrue();
        assertThat(tools.validate(call("validate", Map.of("script", "",
                "catalog", Map.of("wh", "x")))).isError()).isTrue();
        assertThat(tools.validate(call("validate", Map.of("script", "",
                "catalog", Map.of("wh", Map.of("t", Map.of()))))).isError()).isTrue();
        assertThat(tools.validate(call("validate", Map.of("script", "",
                "catalog", Map.of("wh", Map.of("t", Map.of("c", 3)))))).isError()).isTrue();
    }

    @Test
    void learnWithNoTopicListsThePages() {
        String contents = text(tools.learn(call("learn", Map.of())));

        assertThat(contents).contains("Selection").contains("operators/select.md");
    }

    @Test
    void learnFindsAPageByGlyphKeywordOrPath() {
        String byGlyph = text(tools.learn(call("learn", Map.of("topic", "σ"))));

        assertThat(byGlyph).contains("Selection");
        assertThat(text(tools.learn(call("learn", Map.of("topic", "SELECT"))))).isEqualTo(byGlyph);
        assertThat(text(tools.learn(call("learn", Map.of("topic", "operators/select.md")))))
                .isEqualTo(byGlyph);
    }

    @Test
    void aBriefPageIsItsSyntaxAndOneExample() {
        String full = text(tools.learn(call("learn", Map.of("topic", "σ"))));
        String brief = text(tools.learn(call("learn", Map.of("topic", "σ", "detail", "brief"))));

        assertThat(brief).startsWith("# Name:").contains("# Syntax:").contains("# Example:")
                .contains("σ age >= 18 (Users)").doesNotContain("# Description:")
                .doesNotContain("Customers in a specific city")
                .endsWith("without detail 'brief' for the whole page.)");
        assertThat(brief.length()).isLessThan(full.length() / 4);
        assertThat(text(tools.learn(call("learn", Map.of("topic", "σ", "detail", "full")))))
                .isEqualTo(full);
    }

    @Test
    void everyPageHasABriefForm() {
        List<String> pages = new ArrayList<>();
        RelixDocs.referencePages().forEach(p -> pages.add(p.path()));
        pages.addAll(List.of("round", "coalesce"));
        for (String page : pages) {
            String brief = text(tools.learn(call("learn", Map.of("topic", page, "detail", "brief"))));
            assertThat(brief).as(page).contains("# Syntax:").contains("# Example:")
                    .doesNotContain("# Description:");
            String example = brief.substring(brief.indexOf("# Example:"));
            assertThat(example.lines()).as(page + ": the example holds code")
                    .anyMatch(l -> l.startsWith(" ") || l.startsWith("```"));
        }
    }

    @Test
    void learnFindsAFunctionPageFromItsLibrary() {
        String page = text(tools.learn(call("learn", Map.of("topic", "round"))));

        assertThat(page).doesNotContain("No reference page").contains("Round");
        assertThat(text(tools.learn(call("learn", Map.of())))).contains("Round");
    }

    @Test
    void aMissPointsAtTheSpellingsPage() {
        assertThat(text(tools.learn(call("learn", Map.of("topic", "zzzzqqq")))))
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
    void anAsciiKeywordLandsOnItsPageNotTheQuantifier() {
        // "anti" is inside "quantifier"; asking for the ASCII keyword ANTI must land on
        // the anti join. The engine registers each operator's ASCII spelling as a page
        // key, so ANTI now resolves to the page directly rather than through search.
        String answer = text(tools.learn(call("learn", Map.of("topic", "ANTI"))));
        assertThat(answer).contains("Anti Join").contains("▷")
                .doesNotContain("Universal Quantification");
    }

    @Test
    void aSearchShowsTheSummaryAndTheLineThatMatched() {
        String answer = text(tools.learn(call("learn", Map.of("topic", "pairwise test combinations"))));

        assertThat(answer).contains("- Cover [advanced/cover.md]: Combinatorial")
                .contains("  > ").contains("pairwise");
    }

    @Test
    void aTopicNoPageMentionsSaysSo() {
        assertThat(text(tools.learn(call("learn", Map.of("topic", "zzzzqqq")))))
                .contains("No page mentions it").contains("no topic");
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
        String grammar = text(tools.learn(call("learn", Map.of("topic", Tools.GRAMMAR_PAGE))));

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
        return text(tools.learn(call("learn", Map.of("topic", request)))).lines()
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
