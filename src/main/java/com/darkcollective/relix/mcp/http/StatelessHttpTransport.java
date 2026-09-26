package com.darkcollective.relix.mcp.http;

import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.server.McpStatelessServerHandler;
import io.modelcontextprotocol.server.transport.ServerTransportSecurityException;
import io.modelcontextprotocol.server.transport.ServerTransportSecurityValidator;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpSchema.JSONRPCMessage;
import io.modelcontextprotocol.spec.McpSchema.JSONRPCNotification;
import io.modelcontextprotocol.spec.McpSchema.JSONRPCRequest;
import io.modelcontextprotocol.spec.McpSchema.JSONRPCResponse;
import io.modelcontextprotocol.spec.McpStatelessServerTransport;
import reactor.core.publisher.Mono;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * MCP's Streamable HTTP transport, stateless: one POST carries one JSON-RPC message, and
 * the answer to a request is the response body, as {@code application/json}.
 *
 * <p>It is a function from a request to a {@link Response} and knows no HTTP server.
 * {@link LocalHttpServer} serves it with the JDK's own server, and a serverless handler
 * calls the same {@link #handle} with what its platform hands it. Keeping the server out
 * is what lets both be the same code; the SDK's own stateless transport is a servlet, and
 * a function platform does not run servlets.
 *
 * <p>Nothing is kept between requests: there is no session id and no event stream, so
 * {@code GET} (which opens a stream) is refused with 405, as the protocol allows. A
 * client that asks for both {@code application/json} and {@code text/event-stream}, as
 * clients must, gets the first.
 */
public final class StatelessHttpTransport implements McpStatelessServerTransport {

    /**
     * The largest request body accepted, in bytes. A script long enough to need more is
     * not one a model wrote to be checked; the cap is what keeps a request from being a
     * way to make the server hold an arbitrary amount of memory.
     */
    public static final int MAX_BODY_BYTES = 1024 * 1024;

    private static final String JSON = "application/json";

    private final McpJsonMapper json;
    private final ServerTransportSecurityValidator security;
    private volatile McpStatelessServerHandler handler;
    private volatile boolean closing;

    /**
     * A transport checking each request's headers with {@code security}.
     *
     * @param json     reads and writes the JSON-RPC messages; must not be null
     * @param security checks each request's {@code Origin} and {@code Host}; must not be
     *                 null, and {@link ServerTransportSecurityValidator#NOOP} checks nothing
     */
    public StatelessHttpTransport(McpJsonMapper json, ServerTransportSecurityValidator security) {
        this.json = Objects.requireNonNull(json, "json");
        this.security = Objects.requireNonNull(security, "security");
    }

    /**
     * An HTTP response: its status, its headers, and its body, which is null when it has
     * none.
     *
     * @param status  the status code
     * @param headers the headers, by name
     * @param body    the body, or null
     */
    public record Response(int status, Map<String, String> headers, String body) {

        /** Copies the headers, so the record cannot change under its holder. */
        public Response {
            headers = Map.copyOf(headers);
        }

        private static Response json(int status, String body) {
            return new Response(status, Map.of("Content-Type", JSON + "; charset=utf-8"), body);
        }

        private static Response empty(int status) {
            return new Response(status, Map.of(), null);
        }

        private static Response text(int status, String message) {
            return new Response(status, Map.of("Content-Type", "text/plain; charset=utf-8"),
                    message);
        }
    }

    @Override
    public void setMcpHandler(McpStatelessServerHandler handler) {
        this.handler = handler;
    }

    @Override
    public Mono<Void> closeGracefully() {
        return Mono.fromRunnable(() -> closing = true);
    }

    /**
     * Answers one HTTP request to the MCP endpoint.
     *
     * @param method  the HTTP method
     * @param headers the request headers, by name in any case
     * @param body    the request body, or null when it has none
     * @return the response to send
     */
    public Response handle(String method, Map<String, List<String>> headers, String body) {
        if (closing || handler == null) {
            return Response.text(503, "The server is shutting down.");
        }
        if (!"POST".equals(method)) {
            return new Response(405, Map.of("Allow", "POST"), null);
        }
        try {
            security.validateHeaders(headers);
        } catch (ServerTransportSecurityException e) {
            return Response.text(e.getStatusCode(), e.getMessage());
        }
        String accept = header(headers, "Accept");
        if (accept != null && !acceptsJson(accept)) {
            return Response.text(406, "The server answers in " + JSON + ".");
        }
        String contentType = header(headers, "Content-Type");
        if (contentType != null && !contentType.toLowerCase(Locale.ROOT).startsWith(JSON)) {
            return Response.text(415, "Send one JSON-RPC message as " + JSON + ".");
        }
        if (body == null || body.isBlank()) {
            return error(400, null, McpSchema.ErrorCodes.INVALID_REQUEST,
                    "The body must be one JSON-RPC message.");
        }
        if (body.getBytes(StandardCharsets.UTF_8).length > MAX_BODY_BYTES) {
            return Response.text(413, "A request may be at most " + MAX_BODY_BYTES + " bytes.");
        }

        JSONRPCMessage message;
        try {
            message = McpSchema.deserializeJsonRpcMessage(json, body);
        } catch (IOException | IllegalArgumentException e) {
            return error(400, null, McpSchema.ErrorCodes.PARSE_ERROR,
                    "The body is not a JSON-RPC message.");
        }
        return switch (message) {
            case JSONRPCRequest request -> answer(request);
            case JSONRPCNotification notification -> accept(notification);
            // A response answers a request, and a stateless server sends none.
            default -> error(400, null, McpSchema.ErrorCodes.INVALID_REQUEST,
                    "The server takes a request or a notification.");
        };
    }

    private Response answer(JSONRPCRequest request) {
        McpTransportContext context = McpTransportContext.EMPTY;
        try {
            JSONRPCResponse response = handler.handleRequest(context, request)
                    .contextWrite(ctx -> ctx.put(McpTransportContext.KEY, context))
                    .block();
            return Response.json(200, json.writeValueAsString(response));
        } catch (Exception e) {
            return error(500, request.id(), McpSchema.ErrorCodes.INTERNAL_ERROR,
                    "The request failed: " + e.getMessage());
        }
    }

    private Response accept(JSONRPCNotification notification) {
        McpTransportContext context = McpTransportContext.EMPTY;
        try {
            handler.handleNotification(context, notification)
                    .contextWrite(ctx -> ctx.put(McpTransportContext.KEY, context))
                    .block();
            return Response.empty(202);
        } catch (Exception e) {
            return error(500, null, McpSchema.ErrorCodes.INTERNAL_ERROR,
                    "The notification failed: " + e.getMessage());
        }
    }

    /**
     * A JSON-RPC error. Written as a map rather than a {@link JSONRPCResponse}, which
     * refuses the null id JSON-RPC requires when the request's own id is unknown.
     */
    private Response error(int status, Object id, int code, String message) {
        Map<String, Object> error = new LinkedHashMap<>();
        error.put("code", code);
        error.put("message", message);
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("jsonrpc", McpSchema.JSONRPC_VERSION);
        envelope.put("id", id);
        envelope.put("error", error);
        try {
            return Response.json(status, json.writeValueAsString(envelope));
        } catch (IOException e) {
            return Response.text(status, message);
        }
    }

    private static boolean acceptsJson(String accept) {
        String lower = accept.toLowerCase(Locale.ROOT);
        return lower.contains(JSON) || lower.contains("application/*") || lower.contains("*/*");
    }

    private static String header(Map<String, List<String>> headers, String name) {
        for (Map.Entry<String, List<String>> entry : headers.entrySet()) {
            if (name.equalsIgnoreCase(entry.getKey()) && entry.getValue() != null
                    && !entry.getValue().isEmpty()) {
                return String.join(", ", entry.getValue());
            }
        }
        return null;
    }
}
