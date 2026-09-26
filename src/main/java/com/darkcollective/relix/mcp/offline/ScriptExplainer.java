package com.darkcollective.relix.mcp.offline;

import com.darkcollective.relix.embed.Relation;
import com.darkcollective.relix.embed.Relix;
import com.darkcollective.relix.embed.RelixException;
import com.darkcollective.relix.optimizer.TransformationRecord;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Shows how the engine would run a script, without running it or reaching anything it
 * names.
 *
 * <p>For each {@code query}: the query as written, each rewrite the optimiser applied,
 * the rewritten query, and the physical plan. Against a database connection the plan
 * shows what would be pushed to the database and the SQL it would be sent as, in the
 * dialect the connection's URL or {@code dialect:} names. So a model can see the SQL its
 * script would send to the caller's own database, from a machine that cannot reach it.
 *
 * <p>Nothing is executed. The session is an {@link OfflineSession}, and the terminals
 * called on each relation, {@code render()}, {@code optimized()} and {@code explain()},
 * plan from the caller's catalog and the cost model rather than from the data.
 */
public final class ScriptExplainer {

    private final ScriptValidator validator = new ScriptValidator();

    /**
     * Explains a script.
     *
     * @param script  the script's text; must not be null
     * @param files   the text of every file it imports, keyed by the path its
     *                {@code import} statement writes; must not be null, may be empty
     * @param catalog the tables of the connections it reads; must not be null
     * @return the plan of each query, or the findings that stopped the script
     */
    public ExplainReport explain(String script, Map<String, String> files, CallerCatalog catalog) {
        Objects.requireNonNull(script, "script");
        // Validation first, so an invalid script gets every problem at once, placed, rather
        // than whichever one stopped the planner.
        ValidationReport validation = validator.validate(script, files, catalog);
        if (!validation.valid()) {
            return new ExplainReport(false, validation.findings(), List.of());
        }
        List<QueryPlan> queries = new ArrayList<>();
        try (Relix relix = OfflineSession.open(ScriptFiles.of(files), catalog)) {
            List<Relation> relations = relix.script(script);
            List<String> names = QueryNames.of(script);
            for (int i = 0; i < relations.size(); i++) {
                queries.add(plan(i < names.size() ? names.get(i) : null, relations.get(i)));
            }
        }
        return new ExplainReport(true, validation.findings(), queries);
    }

    private static QueryPlan plan(String name, Relation query) {
        try {
            Relation optimised = query.optimized();
            List<String> rewrites = optimised.rewrites().stream()
                    .map(ScriptExplainer::rewrite).toList();
            return new QueryPlan(name, query.render(), rewrites, optimised.render(),
                    optimised.explain(), null);
        } catch (RelixException e) {
            return QueryPlan.failed(name, e.getMessage());
        }
    }

    private static String rewrite(TransformationRecord record) {
        return record.code().code() + ": " + record.detail();
    }
}
