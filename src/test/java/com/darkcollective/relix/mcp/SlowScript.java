package com.darkcollective.relix.mcp;

import java.util.stream.Collectors;
import java.util.stream.IntStream;

/** A script whose second query runs far longer than any test waits, for tests of stopping. */
public final class SlowScript {

    /**
     * One quick query, then one that passes a hundred million rows between its operators
     * while returning none, then another quick one. Run it in a sandbox with no limit on
     * processed rows, or the sandbox stops it before any deadline does.
     */
    public static final String TEXT = """
            D := [
            | a |
            |---|
            %s
            ];
            query { GROUP COUNT(*) -> n (D) };
            query { SELECT a + b + c + d + e + f + g + h < 0 (%s) };
            query { D };
            """.formatted(
            IntStream.range(0, 10).mapToObj(i -> "| " + i + " |").collect(Collectors.joining("\n")),
            "D" + "bcdefgh".chars().mapToObj(c -> " CROSS (RENAME (a -> " + (char) c + ") (D))")
                    .collect(Collectors.joining()));

    private SlowScript() {
    }
}
