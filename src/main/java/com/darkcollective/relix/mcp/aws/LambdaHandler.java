package com.darkcollective.relix.mcp.aws;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestStreamHandler;
import com.darkcollective.relix.mcp.Deadline;
import com.darkcollective.relix.mcp.Main;
import com.darkcollective.relix.mcp.http.ApiKeyAuthenticator;
import com.darkcollective.relix.mcp.http.Authenticator;
import com.darkcollective.relix.mcp.http.StatelessHttpTransport;
import com.darkcollective.relix.mcp.http.StatelessHttpTransport.Response;
import com.darkcollective.relix.mcp.run.ScriptRunner;
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.server.transport.ServerTransportSecurityValidator;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Serves the tools from AWS Lambda, behind a Function URL or anything else that sends
 * payload format 2.0 events.
 *
 * <p>A thin adapter: it turns the event into a call to {@link StatelessHttpTransport#handle}
 * and the {@link Response} back into the event's answer. Before that, the
 * {@link Authenticator} sees the headers, and a refused request gets its response with no
 * MCP processing at all. The request is given a {@link Deadline} just inside the time the
 * platform has left, so a call that runs too long is answered by the server, with what
 * finished, rather than cut off by the platform with a generic error. Raising the
 * function's timeout raises the deadline with it.
 *
 * <p>Configured by the function's environment:
 * <ul>
 *   <li>{@value #KEY_PARAMETER}: the Parameter Store parameter holding the API key, or</li>
 *   <li>{@value #KEY_SECRET}: the Secrets Manager secret holding it. Exactly one of the two
 *       is required, and the key is read once, when the function starts, through the AWS
 *       Parameters and Secrets extension.</li>
 *   <li>{@value #PATH_VARIABLE}: the path the server answers at, {@value #PATH} unless it
 *       says another; behind a CDN that forwards its own path, such as
 *       {@code /mcp/private}, the CDN's.</li>
 *   <li>{@value #QUERY_TIMEOUT}: how many seconds one query of a {@code relix_run} may take;
 *       {@code 0} leaves only the deadline on the whole call. Unset, it is
 *       {@link ScriptRunner#DEFAULT_QUERY_TIMEOUT}.</li>
 * </ul>
 *
 * <p>Under SnapStart, Lambda constructs the handler once, when a version is published, and
 * starts every instance from a snapshot taken after it. So there the constructor also
 * {@linkplain #warm() calls each tool once}, and the snapshot holds an engine that has
 * loaded its classes, found its providers and read its pages. The API key is read then too,
 * and travels in the snapshot: a new key takes effect when a new version is published.
 */
public final class LambdaHandler implements RequestStreamHandler {

    /** The path the server answers at by default, as it does when served locally. */
    public static final String PATH = "/mcp";

    /** The variable giving the path the server answers at. */
    public static final String PATH_VARIABLE = "RELIX_MCP_PATH";

    /** The variable naming the Parameter Store parameter that holds the API key. */
    public static final String KEY_PARAMETER = "RELIX_MCP_API_KEY_PARAMETER";

    /** The variable naming the Secrets Manager secret that holds the API key. */
    public static final String KEY_SECRET = "RELIX_MCP_API_KEY_SECRET";

    /** The variable giving one query's time limit, in seconds. */
    public static final String QUERY_TIMEOUT = "RELIX_MCP_QUERY_TIMEOUT_SECONDS";

    /** The variable in which Lambda says how the function is being started. */
    static final String INITIALIZATION_TYPE = "AWS_LAMBDA_INITIALIZATION_TYPE";

    /**
     * One call to each tool, each needing a different part of the engine: the function
     * library, the solver, the connectors and the Postgres dialect, the reference pages.
     */
    static final List<String> WARM_UP = List.of(
            """
            {"jsonrpc":"2.0","id":1,"method":"initialize","params":{
             "protocolVersion":"2025-06-18","capabilities":{},
             "clientInfo":{"name":"warm-up","version":"1"}}}""",
            call(2, "relix_validate", """
                    {"script":"query { PROJECT Round(2.6) -> r (UNIT) };"}"""),
            call(3, "relix_explain", """
                    {"script":"connection wh from jdbc { url: \\"jdbc:postgresql://db.invalid/wh\\" };\\n\
                    query { SELECT amount > 100 (wh.orders) };",
                     "catalog":{"wh":{"orders":{"amount":"NUMBER"}}}}"""),
            call(4, "relix_run", """
                    {"script":"Backlog := [\\n| feature | value | cost |\\n|---|---|---|\\n\
                    | A | 60 | 10 |\\n| B | 100 | 20 |\\n];\\n\
                    query { OPTIMIZE MAXIMIZE SUM(value) SUBJECT TO SUM(cost) <= 20 (Backlog) };"}"""),
            call(5, "relix_submit", """
                    {"script":"query { PROJECT Round(2.6) -> r (UNIT) };"}"""),
            call(6, "relix_learn", """
                    {"topic":"σ"}"""));

    private static final McpJsonMapper JSON = McpJsonDefaults.getMapper();

    private final Authenticator authenticator;
    private final String path;
    private final StatelessHttpTransport transport;

    /**
     * The handler Lambda constructs, configured by the function's environment. It fails,
     * and so the function fails to start, without a key: it never serves unauthenticated
     * by default.
     */
    public LambdaHandler() {
        this(System.getenv());
    }

    LambdaHandler(Map<String, String> env) {
        this(new ApiKeyAuthenticator(apiKey(env, () -> ParametersAndSecrets.of(env, JSON))),
                new ScriptRunner(ScriptRunner.sandbox(queryTimeout(env))), path(env));
        if ("snap-start".equals(env.get(INITIALIZATION_TYPE))) {
            warm();
        }
    }

    /**
     * A handler letting in what {@code authenticator} allows and running scripts with
     * {@code runner}.
     *
     * @param authenticator decides who may call
     * @param runner        runs the scripts {@code relix_run} is given
     */
    public LambdaHandler(Authenticator authenticator, ScriptRunner runner) {
        this(authenticator, runner, PATH);
    }

    /**
     * A handler letting in what {@code authenticator} allows, running scripts with
     * {@code runner}, and answering at {@code path}.
     *
     * @param authenticator decides who may call
     * @param runner        runs the scripts {@code relix_run} is given
     * @param path          the path the server answers at, such as {@value #PATH}
     */
    public LambdaHandler(Authenticator authenticator, ScriptRunner runner, String path) {
        this.authenticator = Objects.requireNonNull(authenticator, "authenticator");
        this.path = Objects.requireNonNull(path, "path");
        // No Origin or Host check: there is no local name to rebind, and the key, which a
        // browser does not send across origins unasked, is the check.
        this.transport = new StatelessHttpTransport(JSON, ServerTransportSecurityValidator.NOOP);
        Main.statelessServer(transport, runner);
    }

    @Override
    public void handleRequest(InputStream input, OutputStream output, Context context)
            throws IOException {
        Map<?, ?> event = JSON.readValue(new String(input.readAllBytes(), StandardCharsets.UTF_8),
                Map.class);
        Response response = respond(event,
                Deadline.within(Duration.ofMillis(context.getRemainingTimeInMillis())));
        Map<String, Object> answer = new LinkedHashMap<>();
        answer.put("statusCode", response.status());
        answer.put("headers", response.headers());
        if (response.body() != null) {
            answer.put("body", response.body());
        }
        answer.put("isBase64Encoded", false);
        output.write(JSON.writeValueAsString(answer).getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Calls each tool once, as a client would but past the key, so that what the engine
     * loads on first use is loaded now. A call the server cannot answer, or a script it
     * finds invalid or cannot plan or run, fails the warm-up, and with it the version's
     * publication, rather than every request that version would serve.
     */
    void warm() {
        Map<String, List<String>> headers = Map.of(
                "Accept", List.of("application/json, text/event-stream"),
                "Content-Type", List.of("application/json"));
        for (String message : WARM_UP) {
            Response response = transport.handle("POST", headers, message,
                    Deadline.within(Duration.ofSeconds(60)).context());
            String body = response.body();
            if (response.status() != 200 || body == null || body.contains("\"isError\":true")
                    || body.contains("\"valid\":false") || body.contains("Could not be planned: ")
                    || body.contains("Failed: ")) {
                throw new IllegalStateException("the warm-up call " + message
                        + " was answered " + response.status() + ": " + body);
            }
        }
    }

    private static String call(int id, String tool, String arguments) {
        return "{\"jsonrpc\":\"2.0\",\"id\":" + id + ",\"method\":\"tools/call\","
                + "\"params\":{\"name\":\"" + tool + "\",\"arguments\":" + arguments + "}}";
    }

    /**
     * Answers one payload format 2.0 event.
     *
     * @param event    the event, as read from JSON
     * @param deadline when the answer is due
     * @return the response
     */
    Response respond(Map<?, ?> event, Deadline deadline) {
        Map<String, List<String>> headers = headers(event.get("headers"));
        Optional<Response> refused = authenticator.refusal(headers);
        if (refused.isPresent()) {
            return refused.get();
        }
        if (!path.equals(event.get("rawPath"))) {
            return new Response(404, Map.of("Content-Type", "text/plain; charset=utf-8"),
                    "The MCP endpoint is " + path + ".");
        }
        String method = event.get("requestContext") instanceof Map<?, ?> request
                && request.get("http") instanceof Map<?, ?> http
                && http.get("method") instanceof String m ? m : "";
        return transport.handle(method, headers, body(event), deadline.context());
    }

    /**
     * The event's headers. Payload format 2.0 gives each one value, lower-cased, with a
     * repeated header's values joined by commas.
     */
    private static Map<String, List<String>> headers(Object sent) {
        Map<String, List<String>> headers = new LinkedHashMap<>();
        if (sent instanceof Map<?, ?> map) {
            map.forEach((name, value) -> {
                if (name != null && value != null) {
                    headers.put(name.toString(), List.of(value.toString()));
                }
            });
        }
        return headers;
    }

    private static String body(Map<?, ?> event) {
        if (!(event.get("body") instanceof String body)) {
            return null;
        }
        if (Boolean.TRUE.equals(event.get("isBase64Encoded"))) {
            try {
                return new String(Base64.getDecoder().decode(body), StandardCharsets.UTF_8);
            } catch (IllegalArgumentException e) {
                // Left to the transport, which refuses a body it cannot read.
                return body;
            }
        }
        return body;
    }

    /**
     * The API key, from the parameter or the secret the environment names.
     */
    static String apiKey(Map<String, String> env, Supplier<ParametersAndSecrets> extension) {
        String parameter = env.get(KEY_PARAMETER);
        String secret = env.get(KEY_SECRET);
        if ((parameter == null) == (secret == null)) {
            throw new IllegalStateException("set exactly one of " + KEY_PARAMETER + " and "
                    + KEY_SECRET + " to where the API key is held");
        }
        ParametersAndSecrets store = extension.get();
        String key = (parameter != null ? store.parameter(parameter) : store.secret(secret)).strip();
        if (key.isEmpty()) {
            throw new IllegalStateException("the API key is empty");
        }
        return key;
    }

    /**
     * The path to answer at, from the environment.
     */
    static String path(Map<String, String> env) {
        String path = env.get(PATH_VARIABLE);
        if (path == null || path.isBlank()) {
            return PATH;
        }
        if (!path.startsWith("/")) {
            throw new IllegalStateException(PATH_VARIABLE + " is a path from the root, "
                    + "such as /mcp, not '" + path + "'");
        }
        return path;
    }

    /**
     * One query's time limit, from the environment: null for none.
     */
    static Duration queryTimeout(Map<String, String> env) {
        String seconds = env.get(QUERY_TIMEOUT);
        if (seconds == null || seconds.isBlank()) {
            return ScriptRunner.DEFAULT_QUERY_TIMEOUT;
        }
        try {
            long n = Long.parseLong(seconds.strip());
            if (n >= 0) {
                return n == 0 ? null : Duration.ofSeconds(n);
            }
        } catch (NumberFormatException e) {
            // reported below, as a negative number is
        }
        throw new IllegalStateException(QUERY_TIMEOUT + " takes a whole number of seconds, "
                + "or 0 for no limit of its own, not '" + seconds + "'");
    }
}
