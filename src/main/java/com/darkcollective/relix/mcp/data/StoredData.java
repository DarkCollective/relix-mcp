package com.darkcollective.relix.mcp.data;

import java.util.List;
import java.util.Objects;

/**
 * What a data key holds: the declarations of its inline tables, as the caller wrote them,
 * an account of the warnings the engine gave about them when they were loaded, and whether
 * the key is read-only.
 *
 * <p>The warnings themselves are reported once, by {@code relix_load}. Every later call
 * carries one line instead, built from {@code warnings} and {@code warnedTables}, so it
 * needs nothing the store does not already hold.
 *
 * @param declarations the tables' declarations, as text
 * @param warnings     how many warnings the engine gave about them
 * @param warnedTables the tables those warnings are in, in the order they are declared
 * @param readOnly     whether the key's tables may no longer be replaced or added to
 */
public record StoredData(String declarations, int warnings, List<String> warnedTables,
                         boolean readOnly) {

    /**
     * Checks and copies the components.
     */
    public StoredData {
        Objects.requireNonNull(declarations, "declarations");
        warnedTables = List.copyOf(warnedTables);
    }

    /**
     * Data whose key may still be added to.
     *
     * @param declarations the tables' declarations, as text
     * @param warnings     how many warnings the engine gave about them
     * @param warnedTables the tables those warnings are in
     */
    public StoredData(String declarations, int warnings, List<String> warnedTables) {
        this(declarations, warnings, warnedTables, false);
    }

    /**
     * The line every result over this data carries when it has warnings, in place of the
     * warnings themselves.
     *
     * @return the line, or null when the data has no warnings
     */
    public String warningsLine() {
        if (warnings == 0) {
            return null;
        }
        String where = warnedTables.isEmpty() ? ""
                : ", in " + (warnedTables.size() == 1 ? warnedTables.getFirst()
                : String.join(", ", warnedTables.subList(0, warnedTables.size() - 1))
                        + " and " + warnedTables.getLast());
        return "The loaded data has " + warnings + " warning" + (warnings == 1 ? "" : "s") + where
                + ". 'relix_load' listed them; call it again with this key and no tables to "
                + "see them again.";
    }
}
