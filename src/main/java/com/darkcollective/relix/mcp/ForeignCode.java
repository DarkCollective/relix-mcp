package com.darkcollective.relix.mcp;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Recognises a script that is not Relix at all, so that the answer to it says where to go
 * rather than only where the parser stopped.
 *
 * <p>A model handed a tool that runs a {@code script} may send it Python or a shell
 * heredoc. The parse error that comes back names a line and a column, and the model
 * fixes the column. What it needed to hear is that the engine runs Relix only, and that
 * {@code relix_learn} finds the operator for the task it was trying to compute elsewhere.
 *
 * <p>Only an invalid script is ever examined, and the signs looked for are ones Relix
 * cannot contain: Relix also has {@code import} and {@code def}, so a bare {@code import}
 * or {@code def} is not one. A {@code def} line ending in a colon, an {@code import} of a
 * name rather than a quoted file, a {@code #!} line, a heredoc, and a script that begins
 * by naming an interpreter are.
 */
final class ForeignCode {

    private static final List<Pattern> SIGNS = List.of(
            // Starts with an interpreter or shell command, or a shebang.
            Pattern.compile("\\A\\s*(#!|(python3?|bash|sh|zsh|node|ruby|perl)\\b)"),
            // A heredoc: << 'PY', <<EOF, <<-END.
            Pattern.compile("<<-?\\s*['\"]?[A-Za-z_]+['\"]?\\s*$", Pattern.MULTILINE),
            // Python: import of a module name, from-import, a def ending in a colon, print(.
            Pattern.compile("^\\s*import\\s+[A-Za-z_][\\w.]*(\\s+as\\s+\\w+)?\\s*$", Pattern.MULTILINE),
            Pattern.compile("^\\s*from\\s+[A-Za-z_][\\w.]*\\s+import\\s", Pattern.MULTILINE),
            Pattern.compile("^\\s*def\\s+\\w+\\s*\\([^)]*\\)\\s*:\\s*$", Pattern.MULTILINE),
            Pattern.compile("^\\s*print\\s*\\(", Pattern.MULTILINE));

    /**
     * Words in foreign code that point at one operator, and that operator. A model that
     * writes a search over combinations of items is solving what {@code OPTIMIZE} states
     * in one statement.
     */
    private record Hint(Pattern words, String operator, String task) {
    }

    private static final List<Hint> HINTS = List.of(
            new Hint(Pattern.compile("combinations|permutations|knapsack|itertools|maximi[sz]e"
                    + "|minimi[sz]e|linprog|pulp|ortools|branch.and.bound"),
                    "OPTIMIZE", "choosing the best subset or allocation under limits"),
            new Hint(Pattern.compile("dijkstra|shortest.path|networkx|\\bbfs\\b|reachab"),
                    "PATH", "paths and reachability in a graph"),
            new Hint(Pattern.compile("converge|pagerank|game of life|until\\s+stable"),
                    "ITERATE", "repeating a step until it stops changing"));

    private ForeignCode() {
    }

    /**
     * {@return what to tell the sender of {@code script}, when it is plainly not Relix}
     *
     * @param script a script that did not validate
     */
    static Optional<String> pointer(String script) {
        if (SIGNS.stream().noneMatch(sign -> sign.matcher(script).find())) {
            return Optional.empty();
        }
        StringBuilder out = new StringBuilder("This is a Relix engine, not a code runner: it runs "
                + "Relix only, not Python, shell or SQL. Describe the task to 'relix_learn' to "
                + "find the operator for it.");
        String folded = script.toLowerCase(Locale.ROOT);
        HINTS.stream().filter(hint -> hint.words().matcher(folded).find()).findFirst()
                .ifPresent(hint -> out.append(" For ").append(hint.task())
                        .append(", ask 'relix_learn' for ").append(hint.operator()).append('.'));
        return Optional.of(out.toString());
    }

    /**
     * {@return {@code answer}, led by the pointer when {@code script} is plainly not Relix}
     *
     * @param script a script that did not validate
     * @param answer what the tool would otherwise return
     */
    static String lead(String script, String answer) {
        return pointer(script).map(pointer -> pointer + "\n\n" + answer).orElse(answer);
    }
}
