package com.darkcollective.relix.mcp.run;

import com.darkcollective.relix.embed.Diagnostic;
import com.darkcollective.relix.embed.Relation;
import com.darkcollective.relix.embed.Relix;
import com.darkcollective.relix.embed.RelixException;
import com.darkcollective.relix.embed.Rows;
import com.darkcollective.relix.embed.Sandbox;
import com.darkcollective.relix.mcp.data.LoadedData;
import com.darkcollective.relix.mcp.offline.Finding;
import com.darkcollective.relix.mcp.offline.QueryNames;
import com.darkcollective.relix.mcp.offline.SessionClock;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.BooleanSupplier;

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
 *
 * <p>Nothing in the sandbox bounds the whole script: its time limit is per query. A caller
 * that must answer by a deadline says when to stop, and the runner returns what finished.
 */
public final class ScriptRunner {

    /** How long one query may run by default. */
    public static final Duration DEFAULT_QUERY_TIMEOUT = Duration.ofSeconds(10);

    /** The limits this server runs with, unless it is told another time limit. */
    public static final Sandbox DEFAULT_SANDBOX = sandbox(DEFAULT_QUERY_TIMEOUT);

    private final Sandbox sandbox;
    private final Clock clock;

    /**
     * The server's limits, with {@code queryTimeout} as the time one query may run.
     *
     * @param queryTimeout how long one query may run, or null for no limit of its own, when
     *                     a deadline on the whole call bounds it instead
     * @return the sandbox
     */
    public static Sandbox sandbox(Duration queryTimeout) {
        return sandbox(queryTimeout, DEFAULT_MAX_OUTPUT_ROWS);
    }

    /** How many rows one query returns by default; a longer result is cut, and says so. */
    public static final int DEFAULT_MAX_OUTPUT_ROWS = 200;

    /** The variable giving how many rows one query returns. */
    public static final String MAX_OUTPUT_ROWS_VARIABLE = "RELIX_MCP_MAX_OUTPUT_ROWS";

    /**
     * The server's limits, with {@code queryTimeout} as the time one query may run and
     * {@code maxOutputRows} as the rows it returns.
     *
     * <p>The row cap keeps what a model reads short. A client that grades results, such as
     * an evaluation's own server, raises it, so a result is compared whole rather than cut at
     * the cap, where two cuts of the same rows may differ.
     *
     * @param queryTimeout  how long one query may run, or null for no limit of its own
     * @param maxOutputRows how many rows one query returns; positive
     * @return the sandbox
     */
    public static Sandbox sandbox(Duration queryTimeout, int maxOutputRows) {
        Sandbox.Builder builder = Sandbox.builder()
                .maxInputChars(100_000)
                .maxOutputRows(maxOutputRows)
                .maxMaterializedRows(100_000)
                .maxFixpointRounds(1_000)
                .maxProcessedRows(10_000_000);
        if (queryTimeout != null) {
            builder.timeout(queryTimeout);
        }
        return builder.build();
    }

    /**
     * How many rows one query returns, from the environment.
     *
     * @param env the server's environment
     * @return {@value #DEFAULT_MAX_OUTPUT_ROWS}, or what {@value #MAX_OUTPUT_ROWS_VARIABLE} says
     * @throws IllegalStateException if it says anything but a positive whole number
     */
    public static int maxOutputRows(Map<String, String> env) {
        String value = env.get(MAX_OUTPUT_ROWS_VARIABLE);
        if (value == null) {
            return DEFAULT_MAX_OUTPUT_ROWS;
        }
        try {
            int n = Integer.parseInt(value.strip());
            if (n > 0) {
                return n;
            }
        } catch (NumberFormatException e) {
            // reported below, as zero is
        }
        throw new IllegalStateException(MAX_OUTPUT_ROWS_VARIABLE + " takes a positive whole "
                + "number, the most rows one query returns, not '" + value + "'");
    }

    /**
     * A runner with the {@linkplain #DEFAULT_SANDBOX default limits}.
     */
    public ScriptRunner() {
        this(DEFAULT_SANDBOX);
    }

    /**
     * A runner with the given sandbox, whose {@code NOW()} reads the clock the server's
     * environment names ({@link SessionClock}).
     *
     * @param sandbox a closed sandbox; its declarations are what scripts may reach
     */
    public ScriptRunner(Sandbox sandbox) {
        this(sandbox, SessionClock.fromEnvironment());
    }

    /**
     * A runner with the given sandbox and clock.
     *
     * @param sandbox a closed sandbox; its declarations are what scripts may reach
     * @param clock   what a script's {@code NOW()} reads
     */
    public ScriptRunner(Sandbox sandbox, Clock clock) {
        this.sandbox = Objects.requireNonNull(sandbox, "sandbox");
        this.clock = Objects.requireNonNull(clock, "clock");
        if (sandbox.isOpen()) {
            throw new IllegalArgumentException("a runner needs a closed sandbox");
        }
    }

    /**
     * {@return the sandbox scripts run in}
     */
    public Sandbox sandbox() {
        return sandbox;
    }

    /**
     * Validates the script, and when it is valid, runs every {@code query} in it.
     *
     * @param script the script's text; must not be null
     * @return the findings, or each query's rows, or what stopped a query
     */
    public RunReport run(String script) {
        return run(script, () -> false);
    }

    /**
     * Validates the script, and when it is valid, runs its queries until {@code stopped}
     * says to stop.
     *
     * <p>The caller stops a run by making {@code stopped} true and interrupting this
     * thread. The engine notices the interrupt the next time a row passes between two
     * operators and fails the query; because {@code stopped} is true the runner knows why,
     * without reading the failure, and returns the queries that finished before it as a
     * {@linkplain RunReport#stopped() stopped} report.
     *
     * @param script  the script's text; must not be null
     * @param stopped whether to stop; checked before each query and when one fails
     * @return the findings, or each finished query's rows, or what stopped a query
     */
    public RunReport run(String script, BooleanSupplier stopped) {
        return run(script, LoadedData.NONE, stopped);
    }

    /**
     * Validates the script over loaded data, and when it is valid, runs its queries until
     * {@code stopped} says to stop.
     *
     * <p>The data is defined in the sandboxed session as statements, and the script run on
     * its own, as {@link com.darkcollective.relix.mcp.offline.ScriptValidator} checks it: a
     * declaration in it of a loaded table is left out with a warning, and the data's own
     * warnings are left out, because {@code relix_load} reported them.
     *
     * @param script  the script's text; must not be null
     * @param data    the tables the script reads without declaring; {@link LoadedData#NONE}
     *                for none
     * @param stopped whether to stop; checked before each query and when one fails
     * @return the findings, or each finished query's rows, or what stopped a query
     */
    public RunReport run(String script, LoadedData data, BooleanSupplier stopped) {
        Objects.requireNonNull(script, "script");
        Objects.requireNonNull(data, "data");
        Objects.requireNonNull(stopped, "stopped");
        LoadedData.Prepared prepared = data.prepare(script);
        List<Finding> findings = new ArrayList<>(prepared.ignored());
        List<QueryResult> results = new ArrayList<>();
        try (Relix relix = Relix.builder().sandbox(sandbox).clock(clock).build()) {
            data.define(relix);
            // Validation first: the sandbox's refusals are diagnostics there, with the rest
            // of what is wrong, where running would stop at the first.
            List<Diagnostic> diagnostics = prepared.script() != null
                    ? relix.validate(prepared.script()) : relix.validate(script);
            for (Diagnostic diagnostic : diagnostics) {
                if (data.isAbout(diagnostic)) {
                    if (diagnostic.isError()) {
                        findings.add(Finding.of(diagnostic, LoadedData.LABEL::equals));
                    }
                    continue;
                }
                findings.add(Finding.of(diagnostic, path -> false));
            }
            if (findings.stream().anyMatch(Finding::isError)) {
                findings.sort(Comparator.comparing(Finding::isError).reversed());
                return RunReport.invalid(findings);
            }
            List<Relation> queries = prepared.script() != null
                    ? relix.script(prepared.script()) : relix.script(script);
            List<String> names = QueryNames.of(script);
            for (int i = 0; i < queries.size(); i++) {
                String name = i < names.size() ? names.get(i) : null;
                if (stopped.getAsBoolean()) {
                    return RunReport.stopped(findings, results);
                }
                try {
                    Relation query = queries.get(i);
                    Rows rows = query.run();
                    results.add(QueryResult.of(name, query, rows));
                } catch (RelixException e) {
                    if (stopped.getAsBoolean()) {
                        return RunReport.stopped(findings, results);
                    }
                    // A limit reached, or a value the query could not compute: an answer
                    // about this query, and the queries after it may still run.
                    results.add(QueryResult.failed(name, e.getMessage()));
                }
            }
            return RunReport.ran(findings, results);
        } catch (RelixException e) {
            if (stopped.getAsBoolean()) {
                return RunReport.stopped(findings, results);
            }
            return RunReport.invalid(List.of(new Finding(Finding.ERROR, e.getMessage(), null, 0, 0)));
        }
    }
}
