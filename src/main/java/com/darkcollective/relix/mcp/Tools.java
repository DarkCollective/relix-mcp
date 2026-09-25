package com.darkcollective.relix.mcp;

import com.darkcollective.relix.embed.ReferencePage;
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
import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import io.modelcontextprotocol.spec.McpSchema.ToolAnnotations;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * The server's tools: {@code validate}, {@code explain}, {@code run} and {@code learn}.
 *
 * <p>Each is a plain function from arguments to a result, so it is the same whichever
 * transport carries it, and a test calls it without one.
 */
final class Tools {

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
            no FROM, no WHERE clause and no |>. Every operator takes its input relation last, \
            in parentheses, and returns a relation. A script is statements, each ending in ;

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

            Operators (ASCII keyword, then glyph): SELECT cond (R) σ filters rows; \
            PROJECT a, b * 2 -> c (R) π chooses columns; GROUP k, SUM(x) -> total, COUNT(*) -> n (R) γ; \
            SORT x DESC (R) τ; LIMIT n (R) λ; RENAME (old -> new) (R) ρ; DISTINCT (R) δ; \
            A JOIN B ⋈ joins on every shared column name; A >< A.id = B.a_id B joins on a \
            condition written between the inputs; LJOIN, RJOIN, FJOIN (outer) and SEMI, ANTI \
            (keep rows with, or without, a match) take a condition the same way: \
            Customers ANTI Customers.id = Orders.customer_id Orders. UNION, EXCEPT, INTERSECT.
            Predicates: = != < <= > >=, AND, OR, NOT, x IN {1, 2}, x LIKE 'A%', x IS NULL. \
            Sets take braces, not parentheses. <> is not an operator. Comments are -- to end of line.
            Relix also has operators SQL lacks: graph reachability and paths, pairwise test \
            coverage, sessions, time-series downsampling, as-of joins, recursion and more. \
            Before writing code in another language, describe the task to 'learn'.
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

            To see the SQL a script would send to a database, use 'explain'. To see what \
            a script returns, use 'run' with its data as inline tables. Use \
            'learn' for the reference page of any operator, function or keyword.

            """ + PRIMER;

    private static final String EXPLAIN_DESCRIPTION = """
            Shows how the engine would run a Relix script, without running it: for each \
            query, the query as written, each rewrite the optimiser applies (views \
            inlined, selections pushed down, ...), the rewritten query, and the physical \
            plan with its join algorithms and estimated row counts.

            For a database connection, the plan shows what is pushed to the database and \
            the exact SQL it would be sent, in the dialect the connection's URL names \
            (jdbc:postgresql:, jdbc:mysql:, ...). Nothing is contacted, so describe the \
            tables in 'catalog' as for 'validate'. The script is validated first, and an \
            invalid one returns its errors.""";

    private static final String RUN_DESCRIPTION = """
            Runs a Relix script and returns the rows each query statement produces, as a \
            table. Use it to show real results instead of predicting them.

            The script's data must be its own: inline tables, views, and generators. Any \
            file, database or HTTP source, connection, or import is refused, so to run a \
            query meant for real data, write a few representative rows as an inline table. \
            Each query returns at most %d rows (a longer result is cut, and says so) and \
            stops after %d seconds. The script is validated first, and an invalid one \
            returns its errors without running.""".formatted(
            ScriptRunner.DEFAULT_SANDBOX.maxOutputRows().orElseThrow(),
            ScriptRunner.DEFAULT_SANDBOX.timeout().orElseThrow().toSeconds());



    private final ScriptValidator validator = new ScriptValidator();
    private final ScriptRunner runner = new ScriptRunner();
    private final ScriptExplainer explainer = new ScriptExplainer();
    private final Reference reference = new Reference();

    /**
     * {@return every tool the server offers}
     */
    List<SyncToolSpecification> all() {
        return List.of(validateTool(), explainTool(), runTool(), learnTool());
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

    SyncToolSpecification validateTool() {
        Tool tool = Tool.builder("validate", offlineSchema("the Relix script to check"))
                .title("Validate a Relix script")
                .description(VALIDATE_DESCRIPTION)
                .annotations(readOnly("Validate a Relix script"))
                .build();
        return new SyncToolSpecification(tool, (exchange, request) -> validate(request));
    }

    SyncToolSpecification explainTool() {
        Tool tool = Tool.builder("explain", offlineSchema("the Relix script to explain"))
                .title("Explain how a Relix script would run")
                .description(EXPLAIN_DESCRIPTION)
                .annotations(readOnly("Explain how a Relix script would run"))
                .build();
        return new SyncToolSpecification(tool, (exchange, request) -> explain(request));
    }

    SyncToolSpecification runTool() {
        Map<String, Object> schema = Map.of(
                "type", "object",
                "properties", Map.of("script", Map.of("type", "string",
                        "description", "the Relix script to run; its data must be inline tables")),
                "required", List.of("script"));
        Tool tool = Tool.builder("run", schema)
                .title("Run a Relix script over its own data")
                .description(RUN_DESCRIPTION)
                .annotations(readOnly("Run a Relix script over its own data"))
                .build();
        return new SyncToolSpecification(tool, (exchange, request) -> run(request));
    }

    /**
     * The description of {@code learn}, naming the operators SQL has no word for.
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
                your own words, and learn searches every page for it. With no topic, lists \
                every page.

                Relix has operators SQL lacks. Before writing code in another language for \
                something a query does not obviously express, search here. Among them:
                """);
        for (ReferencePage page : reference.category("advanced")) {
            out.append("- ").append(page.title()).append(": ").append(page.summary()).append('\n');
        }
        return out.toString().stripTrailing();
    }

    SyncToolSpecification learnTool() {
        Map<String, Object> schema = Map.of(
                "type", "object",
                "properties", Map.of("topic", Map.of("type", "string",
                        "description", "a glyph, keyword, function name or page "
                                + "path; omit to list every page")));
        Tool tool = Tool.builder("learn", schema)
                .title("Read the Relix reference")
                .description(learnDescription())
                .annotations(readOnly("Read the Relix reference"))
                .build();
        return new SyncToolSpecification(tool, (exchange, request) -> learn(request));
    }

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
        Object parsed = offlineRequest(request, "check");
        if (!(parsed instanceof OfflineRequest asked)) {
            return (CallToolResult) parsed;
        }
        ValidationReport report = validator.validate(asked.script(), asked.files(), asked.catalog());
        return CallToolResult.builder()
                .addTextContent(report.render())
                .structuredContent(Map.of("valid", report.valid(), "findings",
                        report.findings().stream().map(Tools::asData).toList()))
                .build();
    }

    CallToolResult explain(CallToolRequest request) {
        Object parsed = offlineRequest(request, "explain");
        if (!(parsed instanceof OfflineRequest asked)) {
            return (CallToolResult) parsed;
        }
        ExplainReport report = explainer.explain(asked.script(), asked.files(), asked.catalog());
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("valid", report.valid());
        data.put("findings", report.findings().stream().map(Tools::asData).toList());
        data.put("queries", report.queries().stream().map(Tools::asData).toList());
        return CallToolResult.builder()
                .addTextContent(report.render())
                .structuredContent(data)
                .build();
    }

    CallToolResult run(CallToolRequest request) {
        Object script = request.arguments() == null ? null : request.arguments().get("script");
        if (!(script instanceof String text)) {
            return failure("'script' is required: the text of the Relix script to run.");
        }
        RunReport report = runner.run(text);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("valid", report.valid());
        data.put("findings", report.findings().stream().map(Tools::asData).toList());
        data.put("results", report.results().stream().map(Tools::asData).toList());
        return CallToolResult.builder()
                .addTextContent(report.render())
                .structuredContent(data)
                .build();
    }

    CallToolResult learn(CallToolRequest request) {
        Object topic = request.arguments() == null ? null : request.arguments().get("topic");
        if (topic == null || topic.toString().isBlank()) {
            return text(reference.contents());
        }
        String asked = topic.toString();
        return reference.page(asked).map(Tools::text).orElseGet(() -> {
            List<Reference.Hit> hits = reference.search(asked);
            StringBuilder answer = new StringBuilder("No page is called '").append(asked)
                    .append("'.");
            if (hits.isEmpty()) {
                answer.append(" No page mentions it either.");
            } else {
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

    /** A query's result as the JSON object a client receives. */
    private static Map<String, Object> asData(QueryResult result) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("name", result.name());
        data.put("columns", result.columns());
        data.put("rows", result.rows());
        data.put("truncated", result.truncated());
        data.put("failure", result.failure());
        return data;
    }

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
