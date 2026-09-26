package com.darkcollective.relix.mcp.run;

import com.darkcollective.relix.mcp.validate.Finding;

import java.util.List;
import java.util.stream.Collectors;

/**
 * What running one script produced.
 *
 * @param valid    whether the script validated; when false nothing ran and
 *                 {@code findings} says why
 * @param findings the validation errors and warnings
 * @param results  one entry per {@code query} statement, in source order; empty when the
 *                 script was invalid
 */
public record RunReport(boolean valid, List<Finding> findings, List<QueryResult> results) {

    /**
     * Copies the lists.
     */
    public RunReport {
        findings = List.copyOf(findings);
        results = List.copyOf(results);
    }

    static RunReport invalid(List<Finding> findings) {
        return new RunReport(false, findings, List.of());
    }

    static RunReport ran(List<Finding> findings, List<QueryResult> results) {
        return new RunReport(true, findings, results);
    }

    /**
     * {@return the report as text a model reads: each query's rows as a table, or the
     * findings that stopped the script}
     */
    public String render() {
        StringBuilder out = new StringBuilder();
        if (!valid) {
            out.append("Invalid, so nothing ran:\n");
        }
        findings.forEach(f -> out.append(f.render()).append('\n'));
        if (valid && results.isEmpty()) {
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
            out.append('(').append(n).append(n == 1 ? " row" : " rows");
            if (result.truncated()) {
                out.append("; cut at the row limit, so there are more");
            }
            out.append(")\n");
        }
        return out.toString().stripTrailing();
    }

    private static String table(QueryResult result) {
        StringBuilder out = new StringBuilder();
        out.append("| ").append(String.join(" | ", result.columns())).append(" |\n");
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
