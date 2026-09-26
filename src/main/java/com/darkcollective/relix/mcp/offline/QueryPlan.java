package com.darkcollective.relix.mcp.offline;

import java.util.List;

/**
 * How the engine would run one {@code query} statement.
 *
 * @param name      the view the query names, or null for an expression in braces
 * @param written   the query as written, with its views still named
 * @param rewrites  each rewrite the optimiser applied, in order, as {@code CODE: what changed}
 * @param optimised the query after rewriting, views inlined
 * @param plan      the physical plan: join algorithms, what is pushed to each backend and
 *                  the SQL it is sent as, and estimated row counts
 * @param failure   what stopped the engine planning it, or null when it planned
 */
public record QueryPlan(String name, String written, List<String> rewrites, String optimised,
                        String plan, String failure) {

    /**
     * Copies the rewrites.
     */
    public QueryPlan {
        rewrites = List.copyOf(rewrites);
    }

    static QueryPlan failed(String name, String failure) {
        return new QueryPlan(name, null, List.of(), null, null, failure);
    }

    /**
     * {@return whether the query was planned}
     */
    public boolean planned() {
        return failure == null;
    }
}
