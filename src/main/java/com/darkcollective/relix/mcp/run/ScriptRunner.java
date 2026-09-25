package com.darkcollective.relix.mcp.run;

import com.darkcollective.relix.embed.Diagnostic;
import com.darkcollective.relix.embed.Relation;
import com.darkcollective.relix.embed.Relix;
import com.darkcollective.relix.embed.RelixException;
import com.darkcollective.relix.embed.Rows;
import com.darkcollective.relix.embed.Sandbox;
import com.darkcollective.relix.lang.ast.NamedQueryTarget;
import com.darkcollective.relix.lang.ast.QueryStatement;
import com.darkcollective.relix.lang.ast.Statement;
import com.darkcollective.relix.mcp.validate.Finding;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Runs a script whose data is all its own, and returns the rows each query produces.
 *
 * <p>The session is closed by a {@link Sandbox} that permits <em>no</em> external
 * declarations. A sandbox always accepts what reads nothing outside the session (inline
 * tables, views, functions, {@code relate} statements, generator sources), and accepts
 * an external declaration (a file, database or HTTP source, a connection, an
 * {@code import}) only if its own declarations hold the same one. This one holds none, so
 * a script can compute over the data it writes out and can reach nothing else. That makes
 * it safe to run a script from anyone, including a language model.
 *
 * <p>The sandbox's limits bound what one call can cost: how long the text may be, how
 * many rows a query returns (a longer result is cut, and the cut is reported), how much
 * an operator may buffer, how many rounds a recursion may take, how many rows may pass
 * between operators, and how long one query may run. The last two stop a query that works
 * for a long time while producing little, which a cap on output never reaches.
 */
public final class ScriptRunner {

    /** The limits this server runs with. */
    public static final Sandbox DEFAULT_SANDBOX = Sandbox.builder()
            .maxInputChars(100_000)
            .maxOutputRows(200)
            .maxMaterializedRows(100_000)
            .maxFixpointRounds(1_000)
            .maxProcessedRows(10_000_000)
            .timeout(Duration.ofSeconds(10))
            .build();

    private final Sandbox sandbox;

    /**
     * A runner with the {@linkplain #DEFAULT_SANDBOX default limits}.
     */
    public ScriptRunner() {
        this(DEFAULT_SANDBOX);
    }

    /**
     * A runner with the given sandbox.
     *
     * @param sandbox a closed sandbox; its declarations are what scripts may reach
     */
    public ScriptRunner(Sandbox sandbox) {
        this.sandbox = Objects.requireNonNull(sandbox, "sandbox");
        if (sandbox.isOpen()) {
            throw new IllegalArgumentException("a runner needs a closed sandbox");
        }
    }

    /**
     * Validates the script, and when it is valid, runs every {@code query} in it.
     *
     * @param script the script's text; must not be null
     * @return the findings, or each query's rows, or what stopped a query
     */
    public RunReport run(String script) {
        Objects.requireNonNull(script, "script");
        try (Relix relix = Relix.builder().sandbox(sandbox).build()) {
            // Validation first: the sandbox's refusals are diagnostics there, with the rest
            // of what is wrong, where running would stop at the first.
            List<Finding> findings = new ArrayList<>();
            for (Diagnostic diagnostic : relix.validate(script)) {
                findings.add(Finding.of(diagnostic, path -> false));
            }
            if (findings.stream().anyMatch(Finding::isError)) {
                return RunReport.invalid(findings);
            }
            List<Relation> queries = relix.script(script);
            List<String> names = queryNames(script);
            List<QueryResult> results = new ArrayList<>();
            for (int i = 0; i < queries.size(); i++) {
                String name = i < names.size() ? names.get(i) : null;
                try {
                    Rows rows = queries.get(i).run();
                    results.add(QueryResult.of(name, rows));
                } catch (RelixException e) {
                    // A limit reached, or a value the query could not compute: an answer
                    // about this query, and the queries after it may still run.
                    results.add(QueryResult.failed(name, e.getMessage()));
                }
            }
            return RunReport.ran(findings, results);
        } catch (RelixException e) {
            return RunReport.invalid(List.of(new Finding(Finding.ERROR, e.getMessage(), null, 0, 0)));
        }
    }

    /**
     * The view each {@code query} statement names, or null for one written as an
     * expression, in source order: the order {@link Relix#script} returns relations in.
     */
    private static List<String> queryNames(String script) {
        List<String> names = new ArrayList<>();
        for (Statement statement : Relix.parse(script).statements()) {
            if (statement instanceof QueryStatement query) {
                names.add(query.target() instanceof NamedQueryTarget named ? named.name() : null);
            }
        }
        return names;
    }
}
