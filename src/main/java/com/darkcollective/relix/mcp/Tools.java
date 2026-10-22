package com.darkcollective.relix.mcp;

import com.darkcollective.relix.docs.ReferencePage;
import com.darkcollective.relix.mcp.data.DataKeys;
import com.darkcollective.relix.mcp.data.DataStoreException;
import com.darkcollective.relix.mcp.data.LoadReport;
import com.darkcollective.relix.mcp.data.LoadedData;
import com.darkcollective.relix.mcp.data.StoredData;
import com.darkcollective.relix.mcp.http.Authenticator;
import com.darkcollective.relix.mcp.run.QueryResult;
import com.darkcollective.relix.mcp.run.RunReport;
import com.darkcollective.relix.mcp.run.ScriptRunner;
import com.darkcollective.relix.mcp.offline.CallerCatalog;
import com.darkcollective.relix.mcp.offline.ExplainReport;
import com.darkcollective.relix.mcp.offline.QueryPlan;
import com.darkcollective.relix.mcp.offline.ScriptExplainer;
import com.darkcollective.relix.mcp.offline.Finding;
import com.darkcollective.relix.mcp.offline.ScriptValidator;
import com.darkcollective.relix.mcp.offline.ValidationReport;
import com.darkcollective.relix.symbol.ScalarType;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import io.modelcontextprotocol.spec.McpSchema.ToolAnnotations;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * The server's tools: {@code relix_validate}, {@code relix_explain}, {@code relix_run}, {@code relix_submit} and
 * {@code relix_learn}, and, on a server that keeps loaded data, {@code relix_load} and
 * {@code relix_unload}.
 *
 * <p>Each is a plain function from arguments to a result, so it is the same whichever
 * transport carries it, and a test calls it without one. The one thing a tool learns
 * about its caller is the <em>owner</em> its authenticator named, which is whose loaded
 * data it may read.
 */
final class Tools {

    /**
     * A tool as the server lists it, and the function that answers a call to it.
     *
     * <p>The SDK has one specification type per server kind, stdio's taking the session a
     * call arrived on and the stateless server's taking a transport context. No tool here
     * reads either, so a tool is defined once, as this, and each server adapts it.
     *
     * @param tool the tool's name, schema and description
     * @param call answers a call to it
     */
    record Definition(Tool tool, Call call) {
    }

    /**
     * Answers a call to a tool, until told to stop.
     *
     * <p>A caller with a {@link Deadline} makes {@code stopped} true and interrupts the
     * thread when it passes. A tool then returns what finished first, or a result with no
     * content when nothing it can show did, and never the engine's account of the
     * interrupt.
     */
    @FunctionalInterface
    interface Call {

        /**
         * @param request the call
         * @param owner   whose loaded data the call may read, or null when the server
         *                cannot tell its callers apart
         * @param stopped whether the call's deadline has passed
         * @return the answer
         */
        CallToolResult apply(CallToolRequest request, String owner, BooleanSupplier stopped);
    }

    /** A {@code stopped} that never stops. */
    private static final BooleanSupplier NEVER = () -> false;

    /**
     * The primer a model reads before writing its first script.
     *
     * <p>In the tool description because that is the one thing every client reliably
     * shows the model. Every example in it is validated by {@code ToolsTest}: a wrong
     * example in text whose purpose is to be imitated is copied into every script written
     * from it.
     */
    static final String PRIMER = """
            Relix is a relational-algebra language, not SQL and not a pipe language: there is \
            no FROM, no WHERE clause and no |>. An operator on one relation takes it last, in \
            parentheses: SELECT cond (R). An operator on two is written between them, with \
            nothing in front: A JOIN B, A >< cond B, A UNION B. Each returns a relation. A \
            script is statements, each ending in ;

              Orders := [
              | order_id | customer_id | amount |
              |----------|-------------|--------|
              | 1        | 1           | 120    |
              | 2        | 2           | 50     |
              ];
              Big := { SELECT amount > 100 (Orders) };
              query { PROJECT order_id, amount (Big) };

            Data is an inline table (above), or a source with its columns declared:
              source Customers from csv("customers.csv") { header: true, schema: { customer_id: NUMBER, name: STRING } };
            A view is Name := { expression }; and the braces are required. A result is \
            query Name; or query { expression };

            Operators (ASCII keyword, then glyph):
            SELECT cond (R) σ keeps the rows where cond holds. It is a filter, not SQL's SELECT.
            PROJECT a, b * 2 -> c (R) π chooses and computes columns: this is SQL's SELECT list.
            GROUP k, SUM(x) -> total, COUNT(*) -> n (R) γ aggregates. SUM, COUNT, AVG, MIN and \
            MAX work only inside GROUP; with no k, GROUP SUM(x) -> total (R) gives one row for \
            the whole relation.
            SORT x DESC (R) τ; LIMIT n (R) λ; RENAME (old -> new) (R) ρ; DISTINCT (R) δ; \
            A JOIN B ⋈ joins on every shared column name; A >< A.id = B.a_id B joins on a \
            condition written between the inputs, and no JOIN goes in front of it; LJOIN, RJOIN, FJOIN (outer) and SEMI, ANTI \
            (keep rows with, or without, a match) take a condition the same way: \
            Customers ANTI Customers.id = Orders.customer_id Orders. UNION, EXCEPT, INTERSECT.
            TOP 2 WITH TIES t PER g (R) keeps, in each g, the 2 rows with the smallest t and \
            every row tied with the 2nd: as in a race, two tied for first fill both places and \
            nobody is 2nd. Without WITH TIES a tie is cut; t DESC keeps the largest. For the top \
            N per group use TOP, not MIN, GROUP or ranks worked out by hand.
            Predicates: = != < <= > >=, AND, OR, NOT, x IN {1, 2}, x LIKE 'A%', x IS NULL. \
            Sets take braces, not parentheses. <> is not an operator. Comments are -- to end of line.
            Relix also has operators SQL lacks, each with its own page: graph reachability \
            and paths (CLOSURE, PATH, TRACE), recursion (FIX), iteration to convergence \
            (ITERATE), optimisation and constraint solving (OPTIMIZE, SOLVE), pairwise test \
            coverage (COVER), sessions (SESSIONIZE), time-series downsampling (DOWNSAMPLE), \
            as-of joins (ASOF), top rows per group (TOP), sampling (SAMPLE), clustering \
            (CLUSTER) and record linkage. Before writing anything, describe the task to \
            'relix_learn': it searches every page, and Relix may have an operator for it. Never \
            hard-code a result computed elsewhere into a script: 'relix_run' would show those rows \
            as if the engine had found them.
            """;

    private static final String VALIDATE_DESCRIPTION = """
            Checks a Relix script without running it: syntax, every relation and column \
            name, types, and each operator's rules. Returns 'Valid.' or the errors, each \
            with its line and column. Nothing the script names is opened or contacted, so \
            it may use your own files, databases and APIs.

            Declare a schema for each file or HTTP source so its columns are checked. For \
            a database connection referenced as conn.table, describe its tables in \
            'catalog' as {"conn": {"table": {"column": "TYPE"}}}; a table not described \
            there is reported as unknown. Send the text of each imported file in 'files'.

            To see the SQL a script would send to a database, use 'relix_explain'. To see what \
            a script returns, use 'relix_run' with its data as inline tables. Use \
            'relix_learn' for the reference page of any operator, function or keyword.

            """ + PRIMER;

    /**
     * What an invalid verdict ends with. A model that has called {@code relix_validate} once tends
     * to treat the call as the check, show its fix unchecked, and so show the same error
     * again; saying so where the error is read is what reaches it.
     */
    static final String REVALIDATE = "Fix these, then call 'relix_validate' again on the fixed "
            + "script: it is ready to show only once this says Valid.";

    private static final String EXPLAIN_DESCRIPTION = """
            Shows how the engine would run a Relix script, without running it: for each \
            query, the query as written, each rewrite the optimiser applies (views \
            inlined, selections pushed down, ...), the rewritten query, and the physical \
            plan with its join algorithms and estimated row counts.

            For a database connection, the plan shows what is pushed to the database and \
            the exact SQL it would be sent, in the dialect the connection's URL names \
            (jdbc:postgresql:, jdbc:mysql:, ...). Nothing is contacted, so describe the \
            tables in 'catalog' as for 'relix_validate'. The script is validated first, and an \
            invalid one returns its errors.""";

    /**
     * The reference page holding the whole language as EBNF. It ships in the engine jar, so
     * {@code relix_learn} serves the grammar of exactly the engine this server runs.
     */
    static final String GRAMMAR_PAGE = "language/grammar.md";

    /**
     * Where the site publishes the language's primer and grammar as plain text, for a
     * reader who can fetch a URL. They describe the site's current release, which is the
     * hosted server's engine but may not be a local install's, so the tools point at
     * {@code relix_learn} for anything a script depends on and at these for further reading.
     */
    static final String SITE = "https://relix.darkcollective.com";
    static final String LLMS_TXT = SITE + "/llms.txt";
    static final String GRAMMAR_TXT = SITE + "/reference/language/grammar.ebnf.txt";

    /**
     * The query the {@code relix_run} description offers for looking at a script's own
     * relations. {@code ToolsTest} runs it inside the sandbox, under the primer's table.
     */
    static final String CATALOG_QUERY = "query { relix.columns };";

    /**
     * What {@code relix_run}'s and {@code relix_submit}'s descriptions begin with. A tool that
     * runs a {@code script} is, to a model, a tool that executes code, and a small model
     * reads the name before the description; so the first thing it reads says what the
     * tool will not run.
     */
    static final String RELIX_ONLY = "Relix only: this does not run Python, shell or SQL.";

    private static final String RUN_DESCRIPTION = RELIX_ONLY + " " + """
            Runs a Relix script and returns the rows each query statement produces, as a \
            table. Use it to show real results instead of predicting them.

            The script's data must be its own: inline tables, views, and generators. Any \
            file, database or HTTP source, connection, or import is refused, so to run a \
            query meant for real data, write a few representative rows as an inline table. \
            Each query %s and %s. There is no need to call 'relix_validate' before 'relix_run': it \
            validates the script first, and an invalid one returns its errors without \
            running.

            Each column heading gives the column's type: an inline table's column is typed \
            from its values, so a date written in one is a STRING unless converted. Each \
            result ends with its order: 'sorted by' the keys the engine guarantees, or 'in \
            no guaranteed order', when rows that look sorted may come back otherwise. Sort \
            the result whose order matters, after any join.

            When the script is finished, call 'relix_submit' with it instead of showing it \
            yourself.

            The engine describes a script's own relations, as relations: %s lists each \
            column with the type the engine gave it, and relix.relations lists the \
            relations with their row counts.""";

    private static final String SUBMIT_DESCRIPTION = RELIX_ONLY + " " + """
            Submits your finished Relix script as the answer. It validates and runs the \
            script exactly as 'relix_run' does, under the same rules, and returns the script \
            together with the rows the engine produced for it, in a fixed form.

            Show the user that output unchanged, as your answer: do not retype the script \
            or the rows, and do not add rows of your own. That way the rows shown are the \
            ones this exact script returns. An invalid script is not submitted: fix what \
            it reports and submit again.""";

    /** What a submitted answer begins with: the one instruction its reader must follow. */
    static final String SUBMITTED = "Submitted. Show the user everything below this line "
            + "unchanged, as your answer: the script, and the rows the engine returned for it.";

    private static final String LOAD_DESCRIPTION = """
            Loads inline tables once, so that scripts read them by a data key instead of \
            declaring them in every call. Send their declarations, each Name := [ ... ];, as \
            'tables'; nothing else can be loaded. It checks them, and returns the key, each \
            table's columns, types and row count, and every warning about the data in full: \
            later results only say how many there are.

            Pass the key as 'data' to 'relix_validate', 'relix_run', 'relix_submit' and \
            'relix_explain', and leave the loaded tables out of the script: a declaration \
            of one there is ignored, with a warning. Send 'key' with 'tables' to add tables \
            to a key, replacing any it holds by the same name, or 'key' alone to see what \
            it holds; a key loaded with 'read_only' can never be changed. A key lasts an \
            hour from its last use. A caller may hold %d at once; 'relix_unload' releases one.""";

    private static final String UNLOAD_DESCRIPTION = """
            Releases a data key from 'relix_load' before it expires, with the tables it \
            holds. Release a key once its tables are no longer needed: a caller may hold \
            only so many at once.""";

    /** What a script tool's description ends with on a server that keeps loaded data. */
    static final String DATA_NOTE = "\n\nTo read tables loaded with 'relix_load', pass their "
            + "key as 'data' and leave their declarations out of the script.";

    private final ScriptValidator validator = new ScriptValidator();
    private final ScriptRunner runner;
    private final ScriptExplainer explainer = new ScriptExplainer();
    private final Reference reference = new Reference();
    private final DataKeys keys;

    /**
     * The tools, {@code relix_run} with the {@linkplain ScriptRunner#DEFAULT_SANDBOX default
     * limits}, keeping no loaded data.
     */
    Tools() {
        this(new ScriptRunner());
    }

    /**
     * The tools, {@code relix_run} with {@code runner}'s limits, keeping no loaded data.
     *
     * @param runner runs the scripts {@code relix_run} is given
     */
    Tools(ScriptRunner runner) {
        this(runner, null);
    }

    /**
     * The tools, {@code relix_run} with {@code runner}'s limits, keeping loaded data in
     * {@code keys}.
     *
     * @param runner runs the scripts {@code relix_run} is given
     * @param keys   where {@code relix_load} keeps data, or null for a server that keeps
     *               none, and so offers neither it nor a {@code data} argument
     */
    Tools(ScriptRunner runner, DataKeys keys) {
        this.runner = runner;
        this.keys = keys;
    }

    /**
     * {@return every tool the server offers}
     */
    List<Definition> all() {
        List<Definition> all = new ArrayList<>(List.of(validateTool(), explainTool(), runTool(),
                submitTool(), learnTool()));
        if (keys != null) {
            all.add(loadTool());
            all.add(unloadTool());
        }
        return List.copyOf(all);
    }

    /**
     * A script tool's arguments: the script, and the key of the loaded data it reads, on a
     * server that keeps any.
     */
    private Map<String, Object> scriptSchema(String scriptDescription, Map<String, Object> more) {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("script", Map.of("type", "string", "description", scriptDescription));
        properties.putAll(more);
        if (keys != null) {
            properties.put("data", Map.of("type", "string",
                    "description", "a data key from relix_load: the script reads the tables "
                            + "loaded under it without declaring them"));
        }
        return Map.of("type", "object", "properties", properties, "required", List.of("script"));
    }

    /**
     * The arguments both offline tools take: the script, the files it imports, and the
     * tables of its connections.
     */
    private Map<String, Object> offlineSchema(String scriptDescription) {
        String types = Arrays.stream(ScalarType.values()).map(Enum::name)
                .collect(Collectors.joining("\", \""));
        Map<String, Object> column = Map.of("type", "string",
                "description", "a column type: \"" + types + "\"");
        return scriptSchema(scriptDescription, Map.of(
                "files", Map.of("type", "object",
                        "description", "the text of each file the script imports, "
                                + "keyed by the path its import statement writes",
                        "additionalProperties", Map.of("type", "string")),
                "catalog", Map.of("type", "object",
                        "description", "the tables of the script's database "
                                + "connections: connection name, then table name, "
                                + "then column name to type",
                        "additionalProperties", Map.of("type", "object",
                                "additionalProperties", Map.of("type", "object",
                                        "additionalProperties", column)))));
    }

    /** A script tool's description, with how to read loaded data on a server that keeps it. */
    private String withData(String description) {
        return keys == null ? description : description + DATA_NOTE;
    }

    Definition validateTool() {
        Tool tool = Tool.builder("relix_validate", offlineSchema("the Relix script to check"))
                .title("Validate a Relix script")
                .description(withData(VALIDATE_DESCRIPTION))
                .annotations(readOnly("Validate a Relix script"))
                .build();
        return new Definition(tool, this::validate);
    }

    Definition explainTool() {
        Tool tool = Tool.builder("relix_explain", offlineSchema("the Relix script to explain"))
                .title("Explain how a Relix script would run")
                .description(withData(EXPLAIN_DESCRIPTION))
                .annotations(readOnly("Explain how a Relix script would run"))
                .build();
        return new Definition(tool, this::explain);
    }

    Definition runTool() {
        Tool tool = Tool.builder("relix_run", scriptSchema("the Relix script to run; its data "
                        + "must be inline tables" + (keys == null ? "" : ", or loaded ones named by 'data'"),
                        Map.of()))
                .title("Run a Relix script over its own data")
                .description(withData(runDescription()))
                .annotations(readOnly("Run a Relix script over its own data"))
                .build();
        return new Definition(tool, this::run);
    }

    Definition submitTool() {
        Tool tool = Tool.builder("relix_submit", scriptSchema("the finished Relix script; its "
                        + "data must be inline tables" + (keys == null ? "" : ", or loaded ones named by 'data'"),
                        Map.of()))
                .title("Submit a finished Relix script with its results")
                .description(withData(SUBMIT_DESCRIPTION))
                .annotations(readOnly("Submit a finished Relix script with its results"))
                .build();
        return new Definition(tool, this::submit);
    }

    Definition loadTool() {
        Map<String, Object> schema = Map.of(
                "type", "object",
                "properties", Map.of(
                        "tables", Map.of("type", "string",
                                "description", "the inline tables to load, each written "
                                        + "Name := [ ... ];"),
                        "key", Map.of("type", "string",
                                "description", "a data key you hold: the tables are added to "
                                        + "it; alone, it shows what the key holds"),
                        "read_only", Map.of("type", "boolean",
                                "description", "true to make the key read-only: no call, "
                                        + "whoever makes it, can then replace or add to its "
                                        + "tables")));
        Tool tool = Tool.builder("relix_load", schema)
                .title("Load inline tables once, to read by a data key")
                .description(LOAD_DESCRIPTION.formatted(keys.maxKeys()))
                .annotations(new ToolAnnotations("Load inline tables once, to read by a data key",
                        false, false, false, false, null))
                .build();
        return new Definition(tool, this::load);
    }

    Definition unloadTool() {
        Map<String, Object> schema = Map.of(
                "type", "object",
                "properties", Map.of("key", Map.of("type", "string",
                        "description", "the data key to release")),
                "required", List.of("key"));
        Tool tool = Tool.builder("relix_unload", schema)
                .title("Release a data key")
                .description(UNLOAD_DESCRIPTION)
                .annotations(new ToolAnnotations("Release a data key", false, true, true, false, null))
                .build();
        return new Definition(tool, this::unload);
    }

    /**
     * The description of {@code relix_run}, with the limits of the sandbox it runs in.
     */
    String runDescription() {
        var sandbox = runner.sandbox();
        String rows = sandbox.maxOutputRows().isPresent()
                ? "returns at most " + sandbox.maxOutputRows().getAsInt()
                        + " rows (a longer result is cut, and says so)"
                : "returns every row";
        String time = sandbox.timeout()
                .map(t -> "stops after " + t.toSeconds() + " seconds")
                .orElse("runs until the call's own time limit");
        return RUN_DESCRIPTION.formatted(rows, time, CATALOG_QUERY);
    }

    /**
     * The description of {@code relix_learn}, naming the operators SQL has no word for.
     *
     * <p>A model that does not know an operator exists will not ask for it; it writes
     * the algorithm in another language instead. So the description says what exists,
     * from the reference's own {@code advanced} pages, their titles and summaries as the
     * engine ships them: the list cannot fall behind the jar the server runs.
     */
    String learnDescription() {
        StringBuilder out = new StringBuilder("""
                Reads the Relix language reference that ships with the engine. Ask for a \
                page by name (an operator glyph like σ, a keyword like SELECT or ROLLING, a \
                function like Round, or a page path), or describe what you want to do, in \
                your own words, and relix_learn searches every page for it. With no topic, lists \
                every page. The whole grammar, as EBNF, is the page '%s'.

                Pages are long, and each one read stays in the conversation. With detail \
                'brief', a page is cut to its syntax and one example; ask for the whole \
                page when you need its rules.

                For a reader that can fetch a URL, the site publishes a primer at %s and \
                the grammar as plain text at %s; they describe the current release.

                Relix has operators SQL lacks. Before writing anything for a task a query \
                does not obviously express, describe the task here. Among them:
                """.formatted(GRAMMAR_PAGE, LLMS_TXT, GRAMMAR_TXT));
        for (ReferencePage page : reference.category("advanced")) {
            out.append("- ").append(page.title()).append(": ").append(page.summary()).append('\n');
        }
        return out.toString().stripTrailing();
    }

    Definition learnTool() {
        Map<String, Object> schema = Map.of(
                "type", "object",
                "properties", Map.of(
                        "topic", Map.of("type", "string",
                                "description", "a glyph, keyword, function name or page "
                                        + "path; omit to list every page"),
                        "detail", Map.of("type", "string", "enum", List.of(FULL, BRIEF),
                                "description", "'full' (the default) for the whole page, "
                                        + "'brief' for its syntax and one example")));
        Tool tool = Tool.builder("relix_learn", schema)
                .title("Read the Relix reference")
                .description(learnDescription())
                .annotations(readOnly("Read the Relix reference"))
                .build();
        return new Definition(tool, (request, owner, stopped) -> learn(request));
    }

    /** The two lengths {@code relix_learn} serves a page at. */
    static final String FULL = "full";
    static final String BRIEF = "brief";

    /** What an offline tool was asked to look at, once its arguments have been read. */
    private record OfflineRequest(String script, Map<String, String> files, CallerCatalog catalog) {
    }

    /**
     * Reads an offline tool's arguments.
     *
     * @return the request, or a tool error saying which argument is wrong and how
     */
    private static Object offlineRequest(CallToolRequest request, String verb) {
        Map<String, Object> args = request.arguments() == null ? Map.of() : request.arguments();
        if (!(args.get("script") instanceof String script)) {
            return failure("'script' is required: the text of the Relix script to " + verb + ".");
        }
        Map<String, String> files = new LinkedHashMap<>();
        if (args.get("files") instanceof Map<?, ?> sent) {
            for (var file : sent.entrySet()) {
                if (!(file.getValue() instanceof String text)) {
                    return failure("'files' maps each imported path to that file's text; "
                            + "the value for '" + file.getKey() + "' is not a string.");
                }
                files.put(String.valueOf(file.getKey()), text);
            }
        } else if (args.get("files") != null) {
            return failure("'files' must be an object mapping each imported path to its text.");
        }
        try {
            CallerCatalog catalog = switch (args.get("catalog")) {
                case null -> CallerCatalog.empty();
                case Map<?, ?> described -> CallerCatalog.of(stringKeys(described));
                default -> throw new IllegalArgumentException(
                        "catalog: must be an object, {\"conn\": {\"table\": {\"column\": \"TYPE\"}}}");
            };
            return new OfflineRequest(script, files, catalog);
        } catch (IllegalArgumentException e) {
            return failure(e.getMessage());
        }
    }

    /**
     * The loaded data a script tool's call names, as its {@code data} argument.
     *
     * @param key    the data key, or null when the call names none
     * @param data   the tables, parsed
     * @param stored what the key holds, or null when the call names none
     */
    private record Named(String key, LoadedData data, StoredData stored) {

        static final Named NONE = new Named(null, LoadedData.NONE, null);

        /** The line a result over this data carries about its warnings, or null for none. */
        String warningsLine() {
            return stored == null ? null : stored.warningsLine();
        }

        /** {@code text}, with the data's warnings line after it when there is one. */
        String after(String text) {
            String line = warningsLine();
            return line == null ? text : text + "\n\n" + line;
        }

        /** Adds the warnings line to a result's data, when there is one. */
        Map<String, Object> into(Map<String, Object> data) {
            String line = warningsLine();
            if (line != null) {
                data.put("dataWarnings", line);
            }
            return data;
        }
    }

    /**
     * Reads a script tool's {@code data} argument and finds what it names.
     *
     * @return the data, {@link Named#NONE} when the call names none, or a tool error
     */
    private Object named(CallToolRequest request, String owner) {
        Object key = request.arguments() == null ? null : request.arguments().get("data");
        if (key == null) {
            return Named.NONE;
        }
        if (keys == null || owner == null) {
            return failure("This server holds no loaded data, so 'data' cannot be used: "
                    + "declare the tables in the script, as inline tables.");
        }
        if (!(key instanceof String sent) || sent.isBlank()) {
            return failure("'data' is a data key from 'relix_load', as a string.");
        }
        Optional<StoredData> stored;
        try {
            stored = keys.find(owner, sent.strip());
        } catch (DataStoreException e) {
            return failure(STORE_FAILED + e.getMessage());
        }
        if (stored.isEmpty()) {
            try {
                return failure(keys.missing(owner, sent.strip()));
            } catch (DataStoreException e) {
                return failure(DataKeys.notFound(sent.strip()));
            }
        }
        return new Named(sent.strip(), LoadedData.of(stored.get().declarations()), stored.get());
    }

    CallToolResult validate(CallToolRequest request) {
        return validate(request, Authenticator.LOCAL, NEVER);
    }

    CallToolResult validate(CallToolRequest request, String owner, BooleanSupplier stopped) {
        Object parsed = offlineRequest(request, "check");
        if (!(parsed instanceof OfflineRequest asked)) {
            return (CallToolResult) parsed;
        }
        Object found = named(request, owner);
        if (!(found instanceof Named named)) {
            return (CallToolResult) found;
        }
        ValidationReport report = validator.validate(asked.script(), asked.files(), asked.catalog(),
                named.data());
        if (stopped.getAsBoolean()) {
            return NOTHING_FINISHED;
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("valid", report.valid());
        data.put("findings", report.findings().stream().map(Tools::asData).toList());
        return CallToolResult.builder()
                .addTextContent(named.after(report.valid() ? report.render(sources(asked, named))
                        : ForeignCode.lead(asked.script(),
                                report.render(sources(asked, named)) + "\n" + REVALIDATE)))
                .structuredContent(named.into(data))
                .build();
    }

    CallToolResult explain(CallToolRequest request) {
        return explain(request, Authenticator.LOCAL, NEVER);
    }

    CallToolResult explain(CallToolRequest request, String owner, BooleanSupplier stopped) {
        Object parsed = offlineRequest(request, "explain");
        if (!(parsed instanceof OfflineRequest asked)) {
            return (CallToolResult) parsed;
        }
        Object found = named(request, owner);
        if (!(found instanceof Named named)) {
            return (CallToolResult) found;
        }
        ExplainReport report = explainer.explain(asked.script(), asked.files(), asked.catalog(),
                named.data());
        if (stopped.getAsBoolean()) {
            return NOTHING_FINISHED;
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("valid", report.valid());
        data.put("findings", report.findings().stream().map(Tools::asData).toList());
        data.put("queries", report.queries().stream().map(Tools::asData).toList());
        return CallToolResult.builder()
                .addTextContent(named.after(report.valid() ? report.render(sources(asked, named))
                        : ForeignCode.lead(asked.script(), report.render(sources(asked, named)))))
                .structuredContent(named.into(data))
                .build();
    }

    CallToolResult run(CallToolRequest request) {
        return run(request, Authenticator.LOCAL, NEVER);
    }

    CallToolResult run(CallToolRequest request, String owner, BooleanSupplier stopped) {
        Object script = request.arguments() == null ? null : request.arguments().get("script");
        if (!(script instanceof String text)) {
            return failure("'script' is required: the text of the Relix script to run.");
        }
        Object found = named(request, owner);
        if (!(found instanceof Named named)) {
            return (CallToolResult) found;
        }
        RunReport report = runner.run(text, named.data(), stopped);
        if (report.stopped() && report.results().isEmpty()) {
            return NOTHING_FINISHED;
        }
        String rendered = report.render(scriptOnly(text, named));
        return CallToolResult.builder()
                .addTextContent(named.after(!report.valid() ? ForeignCode.lead(text, rendered)
                        : ranToTheEnd(report) ? rendered + "\n\n" + SUBMIT_REMINDER : rendered))
                .structuredContent(named.into(asData(report)))
                .build();
    }

    /**
     * What a successful run ends with. A model reads a run's rows, finds them right, and
     * writes them in its reply, never calling relix_submit: the description says to, but
     * not at the moment it decides to stop, which is here.
     */
    static final String SUBMIT_REMINDER = "If these are the rows the question asks for, call "
            + "relix_submit with this same script: a script or rows written in your reply are "
            + "not an answer.";

    /** Whether every query ran and returned its rows, so they could be an answer. */
    private static boolean ranToTheEnd(RunReport report) {
        return report.valid() && !report.stopped() && !report.results().isEmpty()
                && report.results().stream().allMatch(QueryResult::ran);
    }

    CallToolResult submit(CallToolRequest request) {
        return submit(request, Authenticator.LOCAL, NEVER);
    }

    /**
     * Runs the finished script and returns it with its rows, as one answer to be shown
     * as it is.
     *
     * <p>A model that writes its answer itself shows a script it did not last run, or rows
     * it predicted, and sometimes loses the answer in a garbled closing message. Here the
     * script and the engine's rows travel together in a tool result, which a client can
     * read whatever the model says afterwards. Only a valid script that ran to the end is
     * submitted.
     */
    CallToolResult submit(CallToolRequest request, String owner, BooleanSupplier stopped) {
        Object script = request.arguments() == null ? null : request.arguments().get("script");
        if (!(script instanceof String text)) {
            return failure("'script' is required: the text of the finished Relix script.");
        }
        Object found = named(request, owner);
        if (!(found instanceof Named named)) {
            return (CallToolResult) found;
        }
        RunReport report = runner.run(text, named.data(), stopped);
        if (report.stopped() && report.results().isEmpty()) {
            return NOTHING_FINISHED;
        }
        boolean submitted = report.valid() && !report.stopped();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("submitted", submitted);
        data.put("script", text);
        if (named.key() != null) {
            data.put("data", named.key());
        }
        data.putAll(asData(report));
        String rendered = report.render(scriptOnly(text, named));
        String answer = submitted
                ? SUBMITTED + "\n\n" + fenced(text) + "\n\n" + rendered
                : "Not submitted: " + (report.valid()
                        ? "the run was stopped before every query finished. Make the script "
                                + "cheaper, then call 'relix_submit' again."
                        : "fix these, then call 'relix_submit' again on the fixed script.")
                        + "\n\n" + rendered;
        if (!report.valid()) {
            answer = ForeignCode.lead(text, answer);
        }
        return CallToolResult.builder()
                .addTextContent(named.after(answer))
                .structuredContent(named.into(data))
                .build();
    }

    CallToolResult load(CallToolRequest request) {
        return load(request, Authenticator.LOCAL, NEVER);
    }

    /**
     * Checks inline tables and holds them under a data key, or adds them to one, or
     * describes what one holds.
     */
    CallToolResult load(CallToolRequest request, String owner, BooleanSupplier stopped) {
        if (owner == null) {
            return failure(NO_OWNER);
        }
        Map<String, Object> args = request.arguments() == null ? Map.of() : request.arguments();
        if (args.get("tables") != null && !(args.get("tables") instanceof String)) {
            return failure("'tables' is the text of the inline tables to load, as a string.");
        }
        if (args.get("key") != null && !(args.get("key") instanceof String)) {
            return failure("'key' is a data key from 'relix_load', as a string.");
        }
        if (args.get("read_only") != null && !(args.get("read_only") instanceof Boolean)) {
            return failure("'read_only' is true or false.");
        }
        String key = args.get("key") instanceof String sent && !sent.isBlank() ? sent.strip() : null;
        LoadReport report;
        try {
            report = keys.load(owner, key, (String) args.get("tables"),
                    Boolean.TRUE.equals(args.get("read_only")));
        } catch (DataStoreException e) {
            return failure(STORE_FAILED + e.getMessage());
        }
        if (stopped.getAsBoolean()) {
            return NOTHING_FINISHED;
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("loaded", report.loaded());
        data.put("key", report.key());
        data.put("readOnly", report.readOnly());
        data.put("tables", report.tables().stream().map(Tools::asData).toList());
        data.put("replaced", report.replaced());
        data.put("findings", report.findings().stream().map(Tools::asData).toList());
        return CallToolResult.builder()
                .addTextContent(report.render())
                .structuredContent(data)
                .isError(!report.loaded())
                .build();
    }

    CallToolResult unload(CallToolRequest request) {
        return unload(request, Authenticator.LOCAL, NEVER);
    }

    CallToolResult unload(CallToolRequest request, String owner, BooleanSupplier stopped) {
        if (owner == null) {
            return failure(NO_OWNER);
        }
        Object key = request.arguments() == null ? null : request.arguments().get("key");
        if (!(key instanceof String sent) || sent.isBlank()) {
            return failure("'key' is required: the data key to release.");
        }
        boolean released;
        try {
            released = keys.unload(owner, sent.strip());
        } catch (DataStoreException e) {
            return failure(STORE_FAILED + e.getMessage());
        }
        return CallToolResult.builder()
                .addTextContent(released ? "Released the data key " + sent.strip() + "."
                        : keys.missing(owner, sent.strip()))
                .structuredContent(Map.of("released", released))
                .build();
    }

    /** What a call is told when the loaded data could not be reached, before why. */
    static final String STORE_FAILED = "The loaded data could not be reached; try again shortly. ";

    /** What a data tool tells a caller the server cannot tell apart from any other. */
    static final String NO_OWNER = "This server cannot tell its callers apart, so it holds no "
            + "data for them: declare the tables in the script, as inline tables.";

    /**
     * The text each finding of an offline request points into: the script, an imported
     * file the caller sent, or the loaded data, so a finding is shown with its source line.
     */
    private static Function<String, String> sources(OfflineRequest asked, Named named) {
        return file -> file == null ? asked.script()
                : named.key() != null && LoadedData.LABEL.equals(file) ? named.data().text()
                : asked.files().get(file);
    }

    /** The same for a script run on its own, which imports nothing. */
    private static Function<String, String> scriptOnly(String script, Named named) {
        return file -> file == null ? script
                : LoadedData.LABEL.equals(file) ? named.data().text() : null;
    }

    /** A script as a fenced code block, its fence longer than any run of backticks in it. */
    private static String fenced(String script) {
        int longest = 0;
        int run = 0;
        for (char c : script.toCharArray()) {
            run = c == '`' ? run + 1 : 0;
            longest = Math.max(longest, run);
        }
        String fence = "`".repeat(Math.max(3, longest + 1));
        return fence + "relix\n" + script.strip() + "\n" + fence;
    }

    CallToolResult learn(CallToolRequest request) {
        Object topic = request.arguments() == null ? null : request.arguments().get("topic");
        if (topic == null || topic.toString().isBlank()) {
            return text(reference.contents());
        }
        String asked = topic.toString();
        boolean brief = request.arguments().get("detail") instanceof String detail
                && detail.strip().equalsIgnoreCase(BRIEF);
        return reference.page(asked).map(page -> text(brief ? Reference.brief(page)
                + "\n\n(The syntax and one example. Ask learn for '" + asked.strip()
                + "' without detail 'brief' for the whole page.)" : page)).orElseGet(() -> {
            List<Reference.Category> categories = reference.categories(asked);
            Reference.Found found = reference.search(asked);
            List<Reference.Hit> hits = found.hits();
            StringBuilder answer = new StringBuilder("No page is called '").append(asked)
                    .append("'.");
            for (Reference.Category category : categories) {
                answer.append("\n\nThe ").append(category.name())
                        .append(" functions; ask learn for one by its name:\n")
                        .append(String.join(", ", category.functions())).append('\n');
            }
            if (hits.isEmpty()) {
                answer.append(categories.isEmpty() ? " No page mentions it either."
                        : "\nNo page mentions it otherwise.");
            } else {
                if (!categories.isEmpty()) {
                    answer.append('\n');
                }
                answer.append(" These pages mention it, most relevant first; ask learn for ")
                        .append("one by the name in brackets:\n");
                for (Reference.Hit hit : hits) {
                    answer.append("\n- ").append(hit.title()).append(" [").append(hit.where())
                            .append("]: ").append(hit.summary());
                    if (!hit.excerpt().isEmpty()) {
                        answer.append("\n  > ").append(hit.excerpt());
                    }
                }
                answer.append('\n');
                if (found.matched() > hits.size()) {
                    answer.append("\n").append(found.matched() - hits.size())
                            .append(" more pages mention it. Ask in more exact words to ")
                            .append("rank them, or call learn with no topic to list them all.\n");
                }
            }
            reference.spellingsPage().ifPresent(path -> answer.append("\nFor an ASCII ")
                    .append("keyword, '").append(path).append("' gives its glyph, and the ")
                    .append("glyph finds the page."));
            answer.append(" Call learn with no topic to list every page.");
            return text(answer.toString());
        });
    }

    /** A finding as the JSON object a client receives: its components, and no more. */
    private static Map<String, Object> asData(Finding finding) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("severity", finding.severity());
        data.put("message", finding.message());
        data.put("file", finding.file());
        data.put("line", finding.line());
        data.put("column", finding.column());
        return data;
    }

    /** A loaded table as the JSON object a client receives. */
    private static Map<String, Object> asData(LoadReport.Table table) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("name", table.name());
        data.put("rows", table.rows());
        data.put("columns", table.columns());
        data.put("types", table.types());
        data.put("values", table.values());
        return data;
    }

    /** A query's plan as the JSON object a client receives. */
    private static Map<String, Object> asData(QueryPlan plan) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("name", plan.name());
        data.put("written", plan.written());
        data.put("rewrites", plan.rewrites());
        data.put("optimised", plan.optimised());
        data.put("plan", plan.plan());
        data.put("failure", plan.failure());
        return data;
    }

    /** A run as the JSON object a client receives. */
    private static Map<String, Object> asData(RunReport report) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("valid", report.valid());
        data.put("stopped", report.stopped());
        data.put("findings", report.findings().stream().map(Tools::asData).toList());
        data.put("results", report.results().stream().map(Tools::asData).toList());
        return data;
    }

    /** A query's result as the JSON object a client receives. */
    private static Map<String, Object> asData(QueryResult result) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("name", result.name());
        data.put("columns", result.columns());
        data.put("types", result.types());
        data.put("ordering", result.ordering());
        data.put("rows", result.rows());
        data.put("truncated", result.truncated());
        data.put("failure", result.failure());
        return data;
    }

    /**
     * What a tool stopped at its deadline returns when nothing it can show finished: no
     * content, so the deadline's own account is the whole answer.
     */
    private static final CallToolResult NOTHING_FINISHED = CallToolResult.builder().build();

    private static ToolAnnotations readOnly(String title) {
        return new ToolAnnotations(title, true, false, true, false, null);
    }

    private static CallToolResult text(String text) {
        return CallToolResult.builder().addTextContent(text).build();
    }

    private static CallToolResult failure(String message) {
        return CallToolResult.builder().addTextContent(message).isError(true).build();
    }

    private static Map<String, ?> stringKeys(Map<?, ?> map) {
        Map<String, Object> out = new LinkedHashMap<>();
        map.forEach((k, v) -> out.put(String.valueOf(k), v));
        return out;
    }
}
