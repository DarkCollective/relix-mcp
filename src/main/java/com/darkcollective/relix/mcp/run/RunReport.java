package com.darkcollective.relix.mcp.run;

import com.darkcollective.relix.mcp.offline.Finding;

import java.util.List;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * What running one script produced.
 *
 * @param valid    whether the script validated; when false nothing ran and
 *                 {@code findings} says why
 * @param findings the validation errors and warnings
 * @param results  one entry per {@code query} statement, in source order; empty when the
 *                 script was invalid, and only those that finished when it was stopped
 * @param stopped  whether the caller stopped the run before every query had finished
 */
public record RunReport(boolean valid, List<Finding> findings, List<QueryResult> results,
                        boolean stopped) {

    /**
     * Copies the lists.
     */
    public RunReport {
        findings = List.copyOf(findings);
        results = List.copyOf(results);
    }

    static RunReport invalid(List<Finding> findings) {
        return new RunReport(false, findings, List.of(), false);
    }

    static RunReport ran(List<Finding> findings, List<QueryResult> results) {
        return new RunReport(true, findings, results, false);
    }

    static RunReport stopped(List<Finding> findings, List<QueryResult> results) {
        return new RunReport(true, findings, results, true);
    }

    /**
     * {@return the report as text a model reads: each query's rows as a table, or the
     * findings that stopped the script}
     */
    public String render() {
        return render(file -> null);
    }

    /**
     * {@return the report as {@link #render()} gives it, each placed finding followed by
     * the source line it points at}
     *
     * @param sources the text of a finding's file, or of the script for a null file, as
     *                for {@link Finding#render(Function)}
     */
    public String render(Function<String, String> sources) {
        StringBuilder out = new StringBuilder();
        if (!valid) {
            out.append("Invalid, so nothing ran:\n");
        }
        findings.forEach(f -> out.append(f.render(sources)).append('\n'));
        if (valid && !stopped && results.isEmpty()) {
            out.append("The script has no query statement, so there is nothing to show. ")
                    .append("Add one: query Name; or query { expression };\n");
        }
        for (int i = 0; i < results.size(); i++) {
            QueryResult result = results.get(i);
            if (i > 0 || !findings.isEmpty()) {
                out.append('\n');
            }
            out.append("Query ").append(i + 1)
                    .append(result.name() == null ? "" : " (" + result.name() + ")").append(":\n");
            if (!result.ran()) {
                out.append("Failed: ").append(result.failure()).append('\n');
                continue;
            }
            out.append(table(result));
            int n = result.rows().size();
            out.append('(').append(n).append(n == 1 ? " row" : " rows").append(", ")
                    .append(result.ordering().isEmpty() ? "in no guaranteed order"
                            : "sorted by " + String.join(", ", result.ordering()));
            if (result.truncated()) {
                out.append("; cut at the row limit, so there are more");
            }
            out.append(")\n");
        }
        if (stopped) {
            out.append(results.isEmpty() && findings.isEmpty() ? "" : "\n")
                    .append("Stopped before query ").append(results.size() + 1)
                    .append(" finished.\n");
        }
        return out.toString().stripTrailing();
    }

    /**
     * A result as a markdown table. Each heading carries the column's type: a model that
     * cannot see that an inline column is a STRING writes arithmetic on it as if it were
     * a timestamp.
     */
    private static String table(QueryResult result) {
        StringBuilder out = new StringBuilder("|");
        for (int i = 0; i < result.columns().size(); i++) {
            out.append(' ').append(result.columns().get(i)).append(' ')
                    .append(result.types().get(i)).append(" |");
        }
        out.append('\n');
        out.append(result.columns().stream().map(c -> "---")
                .collect(Collectors.joining("|", "|", "|"))).append('\n');
        for (List<Object> row : result.rows()) {
            out.append("| ").append(row.stream()
                    .map(v -> v == null ? "NULL" : v instanceof java.math.BigDecimal d
                            ? d.toPlainString() : v.toString())
                    .collect(Collectors.joining(" | "))).append(" |\n");
        }
        return out.toString();
    }
}
