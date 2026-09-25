package com.darkcollective.relix.mcp.validate;

import java.util.Objects;

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
}
