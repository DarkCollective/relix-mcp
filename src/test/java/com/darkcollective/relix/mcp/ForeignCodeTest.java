package com.darkcollective.relix.mcp;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ForeignCodeTest {

    /** The call from the issue: a Python brute-force search in a bash heredoc. */
    static final String KNAPSACK = """
            python3 - << 'PY'
            import itertools
            items = [
            ('Tent',61,7,8),
            ('Stove',30,3,2),
            ]
            best = max(itertools.combinations(items, 2), key=lambda c: sum(i[1] for i in c))
            print(best)
            PY""";

    @Test
    void aPythonHeredocIsPointedAtLearnAndAtOptimize() {
        assertThat(ForeignCode.pointer(KNAPSACK)).hasValueSatisfying(pointer -> assertThat(pointer)
                .startsWith("This is a Relix engine, not a code runner")
                .contains("'relix_learn'").endsWith("ask 'relix_learn' for OPTIMIZE."));
    }

    @Test
    void eachSignOfAnotherLanguageIsRecognised() {
        for (String script : List.of(
                "#!/usr/bin/env python3\nx = 1",
                "bash -c 'echo hi'",
                "cat <<EOF\nhello\nEOF",
                "import numpy as np\nx = np.zeros(3)",
                "from collections import deque\nq = deque()",
                "def solve(items):\n    return items",
                "x = 1\nprint(x)")) {
            assertThat(ForeignCode.pointer(script)).as(script).isPresent();
        }
    }

    @Test
    void theHintNamesTheOperatorTheCodeWasComputing() {
        assertThat(ForeignCode.pointer("import networkx\nnetworkx.shortest_path(g)"))
                .hasValueSatisfying(p -> assertThat(p).endsWith("for PATH."));
        assertThat(ForeignCode.pointer("def step(grid):\n    pass\n# until stable"))
                .hasValueSatisfying(p -> assertThat(p).endsWith("for ITERATE."));
        assertThat(ForeignCode.pointer("print(1)"))
                .hasValueSatisfying(p -> assertThat(p).endsWith("find the operator for it."));
    }

    /** Relix has import and def too; neither is a sign by itself. */
    @Test
    void relixIsNeverTakenForAnotherLanguage() {
        for (String script : List.of(
                "import \"lib.relix\";\nquery Totals;",
                "import { A, B } from \"lib.relix\";",
                "import Totals from \"lib.relix\";",
                "def f(x: NUMBER): NUMBER := { x * 2 };",
                "def srcs(E: RELATION(src, w: NUMBER)): relation := { π src (E) };",
                "source Orders from csv(\"orders.csv\") { schema: { id: NUMBER } };",
                "query { SELECT amount > 100 (Orders) }",
                "Big := { SELECT amount >> 100 (Orders) };")) {
            assertThat(ForeignCode.pointer(script)).as(script).isEmpty();
        }
        for (String example : ToolsTest.examples(Tools.PRIMER)) {
            assertThat(ForeignCode.pointer(example)).as(example).isEmpty();
        }
    }
}
