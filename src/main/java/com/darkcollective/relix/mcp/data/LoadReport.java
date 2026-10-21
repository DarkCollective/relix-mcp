package com.darkcollective.relix.mcp.data;

import com.darkcollective.relix.mcp.offline.Finding;

import java.util.List;
import java.util.stream.Collectors;

/**
 * What {@code relix_load} did: the key the data is held under and its tables, or why
 * nothing was stored.
 *
 * @param key      the data key, or null when nothing was stored
 * @param tables   each table the key now holds, in the order they are declared
 * @param replaced the tables this load replaced, which the key held before it
 * @param findings every error and warning about the data, errors first, placed in
 *                 {@value LoadedData#LABEL}
 * @param refusal  why the load was refused before the data was checked, or null
 * @param text     the declarations the findings point into
 */
public record LoadReport(String key, List<Table> tables, List<String> replaced,
                         List<Finding> findings, String refusal, String text) {

    /**
     * One table a key holds.
     *
     * @param name    its name
     * @param rows    how many rows it has
     * @param columns its columns' names
     * @param types   each column's type, as a script spells it
     */
    public record Table(String name, int rows, List<String> columns, List<String> types) {

        /** Copies the lists. */
        public Table {
            columns = List.copyOf(columns);
            types = List.copyOf(types);
        }
    }

    /**
     * Copies the lists.
     */
    public LoadReport {
        tables = List.copyOf(tables);
        replaced = List.copyOf(replaced);
        findings = List.copyOf(findings);
    }

    static LoadReport refused(String refusal) {
        return new LoadReport(null, List.of(), List.of(), List.of(), refusal, "");
    }

    static LoadReport invalid(List<Finding> findings, String text) {
        return new LoadReport(null, List.of(), List.of(), findings, null, text);
    }

    /**
     * {@return whether the data is held under {@link #key()}}
     */
    public boolean loaded() {
        return key != null;
    }

    /**
     * {@return the report as text a model reads}
     *
     * <p>A load lists the key, how to use it, each table's columns and row count, the
     * tables replaced, and every warning in full: the one time they are shown in full.
     */
    public String render() {
        if (refusal != null) {
            return refusal;
        }
        String findingsText = findings.stream()
                .map(f -> f.render(file -> LoadedData.LABEL.equals(file) ? text : null))
                .collect(Collectors.joining("\n"));
        if (!loaded()) {
            long errors = findings.stream().filter(Finding::isError).count();
            return "Not loaded: " + errors + " error" + (errors == 1 ? "" : "s")
                    + " in the data. Fix them, then call 'relix_load' again.\n" + findingsText;
        }
        StringBuilder out = new StringBuilder("Loaded under the data key ").append(key)
                .append(". Pass it as 'data' to relix_validate, relix_run, relix_submit and ")
                .append("relix_explain: their scripts then read these tables without ")
                .append("declaring them, and hold only the query.\n");
        for (Table table : tables) {
            out.append("\n").append(table.name()).append(", ").append(table.rows())
                    .append(table.rows() == 1 ? " row: " : " rows: ");
            for (int i = 0; i < table.columns().size(); i++) {
                out.append(i == 0 ? "" : ", ").append(table.columns().get(i)).append(' ')
                        .append(table.types().get(i));
            }
        }
        if (!replaced.isEmpty()) {
            out.append("\n\nReplaced what the key held as ").append(String.join(", ", replaced))
                    .append('.');
        }
        if (!findings.isEmpty()) {
            out.append("\n\n").append(findings.size()).append(" warning")
                    .append(findings.size() == 1 ? "" : "s")
                    .append(" about the data. They are listed only here: later calls say ")
                    .append("how many there are, not what they are.\n").append(findingsText);
        }
        return out.toString();
    }
}
