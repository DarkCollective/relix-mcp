package com.darkcollective.relix.mcp.offline;

import com.darkcollective.relix.embed.Diagnostic;

import java.util.List;
import java.util.Objects;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * One problem with a script, placed where the caller can find it.
 *
 * @param severity {@code error} when the script cannot run as written, {@code warning}
 *                 when it can but probably does not mean what it says
 * @param message  what is wrong, in the engine's words
 * @param file     the imported file the problem is in, or null for the script itself
 * @param line     1-based line, or 0 when the engine could not place it
 * @param column   1-based column, or 0 when the engine could not place it
 */
public record Finding(String severity, String message, String file, int line, int column) {

    /** The severity of a finding that stops the script from running. */
    public static final String ERROR = "error";

    /** The severity of a finding that is suspicious but does not stop the script. */
    public static final String WARNING = "warning";

    /**
     * Checks the components.
     */
    public Finding {
        Objects.requireNonNull(severity, "severity");
        Objects.requireNonNull(message, "message");
    }

    /**
     * The finding for one of the engine's diagnostics.
     *
     * @param diagnostic     what {@code Relix.validate} reported
     * @param isImportedFile whether a location's file is one the caller sent, rather than
     *                       the script itself
     * @return the finding
     */
    public static Finding of(Diagnostic diagnostic, Predicate<String> isImportedFile) {
        String severity = diagnostic.isError() ? ERROR : WARNING;
        return diagnostic.location()
                .map(at -> new Finding(severity, diagnostic.message(),
                        isImportedFile.test(at.filePath()) ? at.filePath() : null,
                        at.line(), at.column()))
                .orElseGet(() -> new Finding(severity, diagnostic.message(), null, 0, 0));
    }

    /**
     * {@return whether this finding stops the script from running}
     */
    public boolean isError() {
        return ERROR.equals(severity);
    }

    /**
     * {@return the finding as one line: where, how bad, and what}
     */
    public String render() {
        StringBuilder where = new StringBuilder(file == null ? "script" : file);
        if (line > 0) {
            where.append(':').append(line).append(':').append(column);
        }
        return where + ": " + severity + ": " + message;
    }

    /** The widest stretch of a source line an excerpt shows. */
    static final int EXCERPT_WIDTH = 120;

    /**
     * The finding as {@link #render()} gives it, followed by the line it points at and a
     * caret under its column.
     *
     * <p>A model reads {@code script:31:31} and has to count lines in a script it wrote as
     * one string, past the inline tables it repeats at the top of every call; it fixes
     * the wrong statement. The excerpt shows which one. It is placed by the finding's
     * line and column alone, never by reading the message.
     *
     * @param sources the text a finding's {@link #file()} names, or the script's text for
     *                a null file; null when the text is not at hand
     * @return the finding, with its source line when the finding is placed and the line
     *         exists
     */
    public String render(Function<String, String> sources) {
        String rendered = render();
        String text = line > 0 ? sources.apply(file) : null;
        if (text == null) {
            return rendered;
        }
        List<String> lines = text.lines().toList();
        if (line > lines.size()) {
            return rendered;
        }
        // A tab is one column; shown as one space it keeps the caret under its character.
        String source = lines.get(line - 1).replace('\t', ' ');
        int at = column - 1;
        int from = 0;
        int to = source.length();
        if (to > EXCERPT_WIDTH) {
            int centre = Math.clamp(at, 0, to);
            from = Math.max(0, Math.min(centre - EXCERPT_WIDTH / 2, to - EXCERPT_WIDTH));
            to = from + EXCERPT_WIDTH;
        }
        String cut = (from > 0 ? "…" : "") + source.substring(from, to)
                + (to < source.length() ? "…" : "");
        if (cut.isBlank()) {
            return rendered;
        }
        StringBuilder out = new StringBuilder(rendered).append("\n  ").append(cut.stripTrailing());
        if (at >= from && at <= to) {
            out.append("\n  ").append(" ".repeat(at - from + (from > 0 ? 1 : 0))).append('^');
        }
        return out.toString();
    }
}
