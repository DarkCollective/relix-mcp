package com.darkcollective.relix.mcp.offline;

import java.util.List;

/**
 * How the engine would run a script.
 *
 * @param valid    whether the script validated; when false nothing was planned and
 *                 {@code findings} says why
 * @param findings the validation errors and warnings
 * @param queries  one entry per {@code query} statement, in source order
 */
public record ExplainReport(boolean valid, List<Finding> findings, List<QueryPlan> queries) {

    /**
     * Copies the lists.
     */
    public ExplainReport {
        findings = List.copyOf(findings);
        queries = List.copyOf(queries);
    }

    /**
     * {@return the report as text a model reads}
     */
    public String render() {
        StringBuilder out = new StringBuilder();
        if (!valid) {
            out.append("Invalid, so nothing was planned:\n");
        }
        findings.forEach(f -> out.append(f.render()).append('\n'));
        if (valid && queries.isEmpty()) {
            out.append("The script has no query statement, so there is nothing to plan. ")
                    .append("Add one: query Name; or query { expression };\n");
        }
        for (int i = 0; i < queries.size(); i++) {
            QueryPlan query = queries.get(i);
            if (i > 0 || !findings.isEmpty()) {
                out.append('\n');
            }
            out.append("Query ").append(i + 1)
                    .append(query.name() == null ? "" : " (" + query.name() + ")").append(":\n");
            if (!query.planned()) {
                out.append("Could not be planned: ").append(query.failure()).append('\n');
                continue;
            }
            section(out, "As written", query.written());
            section(out, "Rewrites", query.rewrites().isEmpty()
                    ? "(none: the optimiser left it as written)"
                    : String.join("\n", query.rewrites()));
            section(out, "Optimised", query.optimised());
            section(out, "Plan", query.plan());
        }
        return out.toString().stripTrailing();
    }

    private static void section(StringBuilder out, String title, String body) {
        out.append(title).append(":\n");
        body.strip().lines().forEach(line -> out.append("  ").append(line).append('\n'));
    }
}
