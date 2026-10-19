package com.darkcollective.relix.mcp.offline;

import org.junit.jupiter.api.Test;

import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;

class FindingTest {

    private static Function<String, String> script(String text) {
        return file -> file == null ? text : null;
    }

    @Test
    void aFindingWithNoPlaceRendersAsBefore() {
        Finding unplaced = new Finding(Finding.ERROR, "something", null, 0, 0);

        assertThat(unplaced.render(script("query { T };"))).isEqualTo(unplaced.render());
    }

    @Test
    void aFindingWithoutItsTextRendersAsBefore() {
        Finding inFile = new Finding(Finding.ERROR, "something", "lib.relix", 1, 3);

        assertThat(inFile.render(script("query { T };"))).isEqualTo(inFile.render());
    }

    @Test
    void aLinePastTheEndOfTheTextIsNotShown() {
        Finding beyond = new Finding(Finding.ERROR, "something", null, 5, 1);

        assertThat(beyond.render(script("query { T };"))).isEqualTo(beyond.render());
    }

    @Test
    void theCaretSitsUnderTheColumn() {
        Finding finding = new Finding(Finding.ERROR, "bad", null, 2, 3);

        assertThat(finding.render(script("A := [| x |\n| 1 |];\nquery { B };")))
                .isEqualTo("script:2:3: error: bad\n  | 1 |];\n    ^");
    }

    @Test
    void aCaretJustPastTheLineMarksItsEnd() {
        Finding finding = new Finding(Finding.ERROR, "expected ;", null, 1, 12);

        assertThat(finding.render(script("query { T }")))
                .endsWith("\n  query { T }\n             ^");
    }

    @Test
    void aTabKeepsTheCaretUnderItsCharacter() {
        Finding finding = new Finding(Finding.ERROR, "bad", null, 1, 3);

        assertThat(finding.render(script("\t\tX")))
                .endsWith("\n    X\n    ^");
    }

    @Test
    void aLongLineIsCutAroundTheColumn() {
        String line = "a".repeat(200) + "X" + "b".repeat(200);
        Finding finding = new Finding(Finding.ERROR, "bad", null, 1, 201);

        String[] shown = finding.render(script(line)).split("\n");

        assertThat(shown).hasSize(3);
        String source = shown[1].substring(2);
        String caret = shown[2].substring(2);
        assertThat(source).startsWith("…").endsWith("…")
                .hasSize(Finding.EXCERPT_WIDTH + 2);
        assertThat(source.charAt(caret.indexOf('^'))).isEqualTo('X');
    }

    @Test
    void aColumnNearTheStartOfALongLineCutsOnlyTheEnd() {
        String line = "X" + "b".repeat(300);
        Finding finding = new Finding(Finding.ERROR, "bad", null, 1, 1);

        String[] shown = finding.render(script(line)).split("\n");

        assertThat(shown[1].substring(2)).startsWith("X").endsWith("…");
        assertThat(shown[2]).isEqualTo("  ^");
    }
}
