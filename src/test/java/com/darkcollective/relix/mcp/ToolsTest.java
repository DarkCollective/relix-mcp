package com.darkcollective.relix.mcp;

import com.darkcollective.relix.mcp.validate.CallerCatalog;
import com.darkcollective.relix.mcp.validate.ScriptValidator;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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
    void theToolsAreValidateAndLearn() {
        assertThat(tools.all()).extracting(t -> t.tool().name())
                .containsExactly("validate", "learn");
        assertThat(tools.all()).allSatisfy(t ->
                assertThat(t.tool().annotations().readOnlyHint()).isTrue());
    }

    @Test
    void validateAnswersInTextAndAsData() {
        CallToolResult result = tools.validate(call("validate",
                Map.of("script", "query { SELECT a > 1 (Nowhere) };")));

        assertThat(result.isError()).isNotEqualTo(Boolean.TRUE);
        assertThat(text(result)).startsWith("Invalid: 1 error.").contains("Nowhere");
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
    void learnSuggestsPagesForATopicWithNoPageOfItsOwn() {
        assertThat(text(tools.learn(call("learn", Map.of("topic", "join")))))
                .satisfiesAnyOf(
                        t -> assertThat(t).contains("# "),
                        t -> assertThat(t).contains("Pages that mention it"));
        assertThat(text(tools.learn(call("learn", Map.of("topic", "zzzzqqq")))))
                .contains("No reference page").contains("no topic");
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
