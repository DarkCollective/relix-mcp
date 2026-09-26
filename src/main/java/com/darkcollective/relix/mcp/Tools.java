package com.darkcollective.relix.mcp;

import com.darkcollective.relix.mcp.run.QueryResult;
import com.darkcollective.relix.mcp.run.RunReport;
import com.darkcollective.relix.mcp.run.ScriptRunner;
import com.darkcollective.relix.mcp.validate.CallerCatalog;
import com.darkcollective.relix.mcp.validate.Finding;
import com.darkcollective.relix.mcp.validate.ScriptValidator;
import com.darkcollective.relix.mcp.validate.ValidationReport;
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
 * The server's tools: {@code validate}, {@code run} and {@code learn}.
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
            condition written between the inputs; LJOIN, RJOIN, FJOIN are outer joins and \
            SEMI, ANTI keep rows with, or without, a match; UNION, EXCEPT, INTERSECT.
            Predicates: = != < <= > >=, AND, OR, NOT, x IN {1, 2}, x LIKE 'A%', x IS NULL. \
            Sets take braces, not parentheses. <> is not an operator. Comments are -- to end of line.
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

            To see what a script returns, use 'run' with its data as inline tables. Use \
            'learn' for the reference page of any operator, function or keyword.

            """ + PRIMER;

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

    private static final String LEARN_DESCRIPTION = """
            Reads the Relix language reference that ships with the engine. With no topic, \
            lists every page. With a topic (an operator glyph like σ, a keyword like \
            SELECT or ROLLING, a function name like Round, or a page path) returns that \
            page, with its syntax and worked examples.""";

    private final ScriptValidator validator = new ScriptValidator();
    private final ScriptRunner runner = new ScriptRunner();
    private final Reference reference = new Reference();

    /**
     * {@return every tool the server offers}
     */
    List<SyncToolSpecification> all() {
        return List.of(validateTool(), runTool(), learnTool());
    }

    SyncToolSpecification validateTool() {
        String types = Arrays.stream(ScalarType.values()).map(Enum::name)
                .collect(Collectors.joining("\", \""));
        Map<String, Object> column = Map.of("type", "string",
                "description", "a column type: \"" + types + "\"");
        Map<String, Object> schema = Map.of(
                "type", "object",
                "properties", Map.of(
                        "script", Map.of("type", "string",
                                "description", "the Relix script to check"),
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
        Tool tool = Tool.builder("validate", schema)
                .title("Validate a Relix script")
                .description(VALIDATE_DESCRIPTION)
                .annotations(readOnly("Validate a Relix script"))
                .build();
        return new SyncToolSpecification(tool, (exchange, request) -> validate(request));
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

    SyncToolSpecification learnTool() {
        Map<String, Object> schema = Map.of(
                "type", "object",
                "properties", Map.of("topic", Map.of("type", "string",
                        "description", "a glyph, keyword, function name or page "
                                + "path; omit to list every page")));
        Tool tool = Tool.builder("learn", schema)
                .title("Read the Relix reference")
                .description(LEARN_DESCRIPTION)
                .annotations(readOnly("Read the Relix reference"))
                .build();
        return new SyncToolSpecification(tool, (exchange, request) -> learn(request));
    }

    CallToolResult validate(CallToolRequest request) {
        Map<String, Object> args = request.arguments() == null ? Map.of() : request.arguments();
        if (!(args.get("script") instanceof String script)) {
            return failure("'script' is required: the text of the Relix script to check.");
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
        CallerCatalog catalog;
        try {
            catalog = switch (args.get("catalog")) {
                case null -> CallerCatalog.empty();
                case Map<?, ?> described -> CallerCatalog.of(stringKeys(described));
                default -> throw new IllegalArgumentException(
                        "catalog: must be an object, {\"conn\": {\"table\": {\"column\": \"TYPE\"}}}");
            };
        } catch (IllegalArgumentException e) {
            return failure(e.getMessage());
        }
        ValidationReport report = validator.validate(script, files, catalog);
        return CallToolResult.builder()
                .addTextContent(report.render())
                .structuredContent(Map.of("valid", report.valid(), "findings",
                        report.findings().stream().map(Tools::asData).toList()))
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
            List<String> near = reference.near(asked);
            StringBuilder answer = new StringBuilder("No reference page is called '")
                    .append(asked).append("'.");
            if (!near.isEmpty()) {
                answer.append(" Pages that mention it: ").append(String.join(", ", near))
                        .append('.');
            }
            reference.spellingsPage().ifPresent(path -> answer.append(" For an ASCII ")
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
