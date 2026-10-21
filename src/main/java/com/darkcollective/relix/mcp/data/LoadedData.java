package com.darkcollective.relix.mcp.data;

import com.darkcollective.relix.embed.Diagnostic;
import com.darkcollective.relix.embed.Relix;
import com.darkcollective.relix.lang.ast.AssignmentStatement;
import com.darkcollective.relix.lang.ast.DefRelationStatement;
import com.darkcollective.relix.lang.ast.InlineTableBody;
import com.darkcollective.relix.lang.ast.Script;
import com.darkcollective.relix.lang.ast.ScriptParseException;
import com.darkcollective.relix.lang.ast.SourceDeclaration;
import com.darkcollective.relix.lang.ast.Statement;
import com.darkcollective.relix.mcp.offline.Finding;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Loaded data, parsed: inline tables a session is given as statements before the script
 * that reads them.
 *
 * <p>The tables are never pasted in front of the script as text. They are parsed under
 * the file name {@value #LABEL} and given to the session with
 * {@link Relix#define(Statement...)}, and the script is checked or run on its own, so a
 * finding in the script is on the script's own line, and a finding about the data is
 * placed in {@value #LABEL}, where it can be told apart.
 *
 * <p>Only inline tables are ever held: {@link #of} refuses anything else, so data defined
 * from here reaches nothing outside the session.
 */
public final class LoadedData {

    /** The file name loaded data is parsed under, and so the file its findings are in. */
    public static final String LABEL = "data";

    /** No data: a script checked or run with this is checked or run as it always was. */
    public static final LoadedData NONE = new LoadedData("", List.of());

    private final String text;
    private final List<AssignmentStatement> tables;

    private LoadedData(String text, List<AssignmentStatement> tables) {
        this.text = text;
        this.tables = List.copyOf(tables);
    }

    /**
     * Parses declarations that are inline tables and nothing else.
     *
     * @param text the declarations
     * @return the data
     * @throws ScriptParseException     if the text does not parse
     * @throws IllegalArgumentException if a statement is not an inline table; {@link
     *                                  #notTables} says which
     */
    public static LoadedData of(String text) {
        Objects.requireNonNull(text, "text");
        Script script = Relix.parse(text, LABEL);
        List<Finding> others = notTables(script);
        if (!others.isEmpty()) {
            throw new IllegalArgumentException(others.getFirst().message());
        }
        return new LoadedData(text, script.statements().stream()
                .map(AssignmentStatement.class::cast).toList());
    }

    /**
     * An error for each statement of {@code script} that is not an inline table.
     *
     * @param script declarations parsed under {@value #LABEL}
     * @return the errors, placed in {@value #LABEL}; empty when every statement is a table
     */
    public static List<Finding> notTables(Script script) {
        List<Finding> others = new ArrayList<>();
        for (Statement statement : script.statements()) {
            if (!(statement instanceof AssignmentStatement assignment
                    && assignment.body() instanceof InlineTableBody)) {
                others.add(new Finding(Finding.ERROR, "only inline tables can be loaded, "
                        + "written Name := [ ... ];. Put "
                        + declared(statement).map(name -> "'" + name + "'").orElse("this statement")
                        + " in the script instead.", LABEL, statement.location().line(),
                        statement.location().column()));
            }
        }
        return others;
    }

    /**
     * {@return the declarations, as text}
     */
    public String text() {
        return text;
    }

    /**
     * {@return whether there are no tables}
     */
    public boolean isEmpty() {
        return tables.isEmpty();
    }

    /**
     * {@return the names of the tables, in the order they are declared}
     */
    public List<String> names() {
        return tables.stream().map(AssignmentStatement::name).distinct().toList();
    }

    /**
     * {@return the number of rows in {@code name}'s declaration, as written}
     *
     * @param name a table's name
     */
    public int rows(String name) {
        return tables.stream().filter(t -> t.name().equals(name))
                .map(t -> ((InlineTableBody) t.body()).table().rows().size())
                .reduce((first, last) -> last).orElse(0);
    }

    /**
     * Defines the tables in {@code relix}.
     *
     * @param relix the session the script will be checked or run in
     */
    public void define(Relix relix) {
        if (!tables.isEmpty()) {
            relix.define(tables.toArray(Statement[]::new));
        }
    }

    /**
     * {@return whether a diagnostic is about this data rather than the script}
     *
     * @param diagnostic what the session reported
     */
    public boolean isAbout(Diagnostic diagnostic) {
        return diagnostic.location().map(at -> LABEL.equals(at.filePath())).orElse(false);
    }

    /**
     * {@return the table a finding about this data on {@code line} is in}
     *
     * @param line a line of {@link #text()}
     */
    public Optional<String> tableAt(int line) {
        String found = null;
        for (AssignmentStatement table : tables) {
            if (table.location().line() <= line) {
                found = table.name();
            }
        }
        return Optional.ofNullable(found);
    }

    /**
     * {@return each table's declaration as written, by name}
     *
     * <p>A declaration runs from where its statement starts to where the next one does,
     * so it is the caller's own text, not a reprinting of it.
     */
    public Map<String, String> declarations() {
        List<Integer> starts = tables.stream()
                .map(t -> offset(t.location().line(), t.location().column())).toList();
        Map<String, String> declarations = new LinkedHashMap<>();
        for (int i = 0; i < tables.size(); i++) {
            int end = i + 1 < starts.size() ? starts.get(i + 1) : text.length();
            String name = tables.get(i).name();
            declarations.remove(name);
            declarations.put(name, text.substring(starts.get(i), end).strip());
        }
        return declarations;
    }

    /** The offset in {@link #text} of a 1-based line and column. */
    private int offset(int line, int column) {
        int at = 0;
        for (int l = 1; l < line; l++) {
            int newline = text.indexOf('\n', at);
            if (newline < 0) {
                return text.length();
            }
            at = newline + 1;
        }
        return Math.min(text.length(), at + Math.max(0, column - 1));
    }

    /**
     * A script ready to be checked or run over this data: parsed, with every statement
     * that declares a loaded table's name left out, and a warning for each one left out.
     *
     * @param script the script's own declarations and queries; null when it does not
     *               parse, or when there is no data, so the text is checked as it is
     * @param ignored a warning for each declaration left out, on the script's own lines
     */
    public record Prepared(Script script, List<Finding> ignored) {

        /** Copies the warnings. */
        public Prepared {
            ignored = List.copyOf(ignored);
        }
    }

    /**
     * Prepares a script to be checked or run over this data.
     *
     * <p>A table that is loaded probably should not be declared in the script too, so a
     * declaration of one is most likely a copy, and perhaps a wrong one. It is reported and
     * ignored, and the script reads the loaded table; the call is not refused.
     *
     * <p>A copy is an inline table or a source, and it is a copy in any case, since Relix
     * names are case-insensitive: {@code ORDERS := [ ... ];} would otherwise replace a loaded
     * {@code orders}. A view, or a relation defined with {@code def}, is left to the engine:
     * named after a loaded table, it is most often a view the writer meant as new, and
     * leaving it out would read the whole loaded table in its place.
     *
     * @param script the script's text
     * @return the script, parsed and with those declarations left out; with no script
     *         when there is no data or the text does not parse, so the session reports
     *         its syntax errors as it always does
     */
    public Prepared prepare(String script) {
        if (tables.isEmpty()) {
            return new Prepared(null, List.of());
        }
        Script parsed;
        try {
            parsed = Relix.parse(script);
        } catch (ScriptParseException e) {
            return new Prepared(null, List.of());
        }
        List<String> names = names();
        List<Statement> kept = new ArrayList<>();
        List<Finding> ignored = new ArrayList<>();
        for (Statement statement : parsed.statements()) {
            Optional<String> name = copied(statement).flatMap(written -> names.stream()
                    .filter(written::equalsIgnoreCase).findFirst()
                    .map(loaded -> loaded.equals(written) ? "'" + loaded + "' is loaded data"
                            : "'" + written + "' is the loaded table '" + loaded
                                    + "' (names are case-insensitive)"));
            if (name.isPresent()) {
                ignored.add(new Finding(Finding.WARNING, name.get() + ", so this declaration "
                        + "of it was ignored and the loaded table read instead. Leave the "
                        + "loaded tables out of the script.", null,
                        statement.location().line(), statement.location().column()));
            } else {
                kept.add(statement);
            }
        }
        return new Prepared(new Script(parsed.namespace(), kept, parsed.comments()), ignored);
    }

    /** The table a statement declares, if it declares data: an inline table or a source. */
    private static Optional<String> copied(Statement statement) {
        return switch (statement) {
            case AssignmentStatement assignment when assignment.body() instanceof InlineTableBody ->
                    Optional.of(assignment.name());
            case SourceDeclaration source -> Optional.of(source.name());
            default -> Optional.empty();
        };
    }

    /** The relation a statement declares, if it declares one. */
    private static Optional<String> declared(Statement statement) {
        return switch (statement) {
            case AssignmentStatement assignment -> Optional.of(assignment.name());
            case DefRelationStatement def -> Optional.of(def.name());
            case SourceDeclaration source -> Optional.of(source.name());
            default -> Optional.empty();
        };
    }
}
