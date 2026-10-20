package com.darkcollective.relix.mcp;

import com.darkcollective.relix.docs.ReferencePage;
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

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * The server's tools: {@code relix_validate}, {@code relix_explain}, {@code relix_run}, {@code relix_submit} and
 * {@code relix_learn}.
 *
 * <p>Each is a plain function from arguments to a result, so it is the same whichever
 * transport carries it, and a test calls it without one.
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
         * @param stopped whether the call's deadline has passed
         * @return the answer
         */
        CallToolResult apply(CallToolRequest request, BooleanSupplier stopped);
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

    private final ScriptValidator validator = new ScriptValidator();
    private final ScriptRunner runner;
    private final ScriptExplainer explainer = new ScriptExplainer();
    private final Reference reference = new Reference();

    /**
     * The tools, {@code relix_run} with the {@linkplain ScriptRunner#DEFAULT_SANDBOX default limits}.
     */
    Tools() {
        this(new ScriptRunner());
    }

    /**
     * The tools, {@code relix_run} with {@code runner}'s limits.
     *
     * @param runner runs the scripts {@code relix_run} is given
     */
    Tools(ScriptRunner runner) {
        this.runner = runner;
    }

    /**
     * {@return every tool the server offers}
     */
    List<Definition> all() {
        return List.of(validateTool(), explainTool(), runTool(), submitTool(), learnTool());
    }

    /**
     * The arguments both offline tools take: the script, the files it imports, and the
     * tables of its connections.
     */
    private static Map<String, Object> offlineSchema(String scriptDescription) {
        String types = Arrays.stream(ScalarType.values()).map(Enum::name)
                .collect(Collectors.joining("\", \""));
        Map<String, Object> column = Map.of("type", "string",
                "description", "a column type: \"" + types + "\"");
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "script", Map.of("type", "string", "description", scriptDescription),
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
                                                "additionalProperties", column)))),
                "required", List.of("script"));
    }

    Definition validateTool() {
        Tool tool = Tool.builder("relix_validate", offlineSchema("the Relix script to check"))
                .title("Validate a Relix script")
                .description(VALIDATE_DESCRIPTION)
                .annotations(readOnly("Validate a Relix script"))
                .build();
        return new Definition(tool, this::validate);
    }

    Definition explainTool() {
        Tool tool = Tool.builder("relix_explain", offlineSchema("the Relix script to explain"))
                .title("Explain how a Relix script would run")
                .description(EXPLAIN_DESCRIPTION)
                .annotations(readOnly("Explain how a Relix script would run"))
                .build();
        return new Definition(tool, this::explain);
    }

    Definition runTool() {
        Map<String, Object> schema = Map.of(
                "type", "object",
                "properties", Map.of("script", Map.of("type", "string",
                        "description", "the Relix script to run; its data must be inline tables")),
                "required", List.of("script"));
        Tool tool = Tool.builder("relix_run", schema)
                .title("Run a Relix script over its own data")
                .description(runDescription())
                .annotations(readOnly("Run a Relix script over its own data"))
                .build();
        return new Definition(tool, this::run);
    }

    Definition submitTool() {
        Map<String, Object> schema = Map.of(
                "type", "object",
                "properties", Map.of("script", Map.of("type", "string",
                        "description", "the finished Relix script; its data must be inline tables")),
                "required", List.of("script"));
        Tool tool = Tool.builder("relix_submit", schema)
                .title("Submit a finished Relix script with its results")
                .description(SUBMIT_DESCRIPTION)
                .annotations(readOnly("Submit a finished Relix script with its results"))
                .build();
        return new Definition(tool, this::submit);
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
        return new Definition(tool, (request, stopped) -> learn(request));
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

    CallToolResult validate(CallToolRequest request) {
        return validate(request, NEVER);
    }

    CallToolResult validate(CallToolRequest request, BooleanSupplier stopped) {
        Object parsed = offlineRequest(request, "check");
        if (!(parsed instanceof OfflineRequest asked)) {
            return (CallToolResult) parsed;
        }
        ValidationReport report = validator.validate(asked.script(), asked.files(), asked.catalog());
        if (stopped.getAsBoolean()) {
            return NOTHING_FINISHED;
        }
        return CallToolResult.builder()
                .addTextContent(report.valid() ? report.render(sources(asked))
                        : ForeignCode.lead(asked.script(),
                                report.render(sources(asked)) + "\n" + REVALIDATE))
                .structuredContent(Map.of("valid", report.valid(), "findings",
                        report.findings().stream().map(Tools::asData).toList()))
                .build();
    }

    CallToolResult explain(CallToolRequest request) {
        return explain(request, NEVER);
    }

    CallToolResult explain(CallToolRequest request, BooleanSupplier stopped) {
        Object parsed = offlineRequest(request, "explain");
        if (!(parsed instanceof OfflineRequest asked)) {
            return (CallToolResult) parsed;
        }
        ExplainReport report = explainer.explain(asked.script(), asked.files(), asked.catalog());
        if (stopped.getAsBoolean()) {
            return NOTHING_FINISHED;
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("valid", report.valid());
        data.put("findings", report.findings().stream().map(Tools::asData).toList());
        data.put("queries", report.queries().stream().map(Tools::asData).toList());
        return CallToolResult.builder()
                .addTextContent(report.valid() ? report.render(sources(asked))
                        : ForeignCode.lead(asked.script(), report.render(sources(asked))))
                .structuredContent(data)
                .build();
    }

    CallToolResult run(CallToolRequest request) {
        return run(request, NEVER);
    }

    CallToolResult run(CallToolRequest request, BooleanSupplier stopped) {
        Object script = request.arguments() == null ? null : request.arguments().get("script");
        if (!(script instanceof String text)) {
            return failure("'script' is required: the text of the Relix script to run.");
        }
        RunReport report = runner.run(text, stopped);
        if (report.stopped() && report.results().isEmpty()) {
            return NOTHING_FINISHED;
        }
        String rendered = report.render(scriptOnly(text));
        return CallToolResult.builder()
                .addTextContent(!report.valid() ? ForeignCode.lead(text, rendered)
                        : ranToTheEnd(report) ? rendered + "\n\n" + SUBMIT_REMINDER : rendered)
                .structuredContent(asData(report))
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
        return submit(request, NEVER);
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
    CallToolResult submit(CallToolRequest request, BooleanSupplier stopped) {
        Object script = request.arguments() == null ? null : request.arguments().get("script");
        if (!(script instanceof String text)) {
            return failure("'script' is required: the text of the finished Relix script.");
        }
        RunReport report = runner.run(text, stopped);
        if (report.stopped() && report.results().isEmpty()) {
            return NOTHING_FINISHED;
        }
        boolean submitted = report.valid() && !report.stopped();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("submitted", submitted);
        data.put("script", text);
        data.putAll(asData(report));
        String answer = submitted
                ? SUBMITTED + "\n\n" + fenced(text) + "\n\n" + report.render(scriptOnly(text))
                : "Not submitted: " + (report.valid()
                        ? "the run was stopped before every query finished. Make the script "
                                + "cheaper, then call 'relix_submit' again."
                        : "fix these, then call 'relix_submit' again on the fixed script.")
                        + "\n\n" + report.render(scriptOnly(text));
        if (!report.valid()) {
            answer = ForeignCode.lead(text, answer);
        }
        return CallToolResult.builder()
                .addTextContent(answer)
                .structuredContent(data)
                .build();
    }

    /**
     * The text each finding of an offline request points into: the script, or an imported
     * file the caller sent, so a finding is shown with its source line.
     */
    private static Function<String, String> sources(OfflineRequest asked) {
        return file -> file == null ? asked.script() : asked.files().get(file);
    }

    /** The same for a script run on its own, which imports nothing. */
    private static Function<String, String> scriptOnly(String script) {
        return file -> file == null ? script : null;
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
