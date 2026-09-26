package com.darkcollective.relix.mcp.http;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.modelcontextprotocol.server.transport.DefaultServerTransportSecurityValidator;
import io.modelcontextprotocol.server.transport.ServerTransportSecurityValidator;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Serves a {@link StatelessHttpTransport} at {@code /mcp} with the JDK's own HTTP server,
 * so the server can be tried over HTTP on one machine before anything is deployed.
 *
 * <p>Bound to a loopback address, as it is by default, it also refuses a request whose
 * {@code Origin} or {@code Host} names anything but this machine. That is the protocol's
 * defence against DNS rebinding, where a web page the user opened sends requests to a
 * server on their own machine under a hostname the page controls.
 */
public final class LocalHttpServer implements AutoCloseable {

    /** The path the endpoint is served at. */
    public static final String PATH = "/mcp";

    private final HttpServer server;
    private final ExecutorService executor;

    private LocalHttpServer(HttpServer server, ExecutorService executor) {
        this.server = server;
        this.executor = executor;
    }

    /**
     * Starts serving.
     *
     * @param address   where to listen; port 0 picks a free one
     * @param transport answers each request; must not be null
     * @return the running server
     * @throws IOException if the address cannot be bound
     */
    public static LocalHttpServer start(InetSocketAddress address, StatelessHttpTransport transport)
            throws IOException {
        Objects.requireNonNull(transport, "transport");
        HttpServer server = HttpServer.create(address, 0);
        ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
        server.setExecutor(executor);
        server.createContext(PATH, exchange -> serve(exchange, transport));
        server.start();
        return new LocalHttpServer(server, executor);
    }

    /**
     * The header checks for a server listening on {@code address}: this machine's names
     * only when it is a loopback address, and none otherwise, since a server reachable
     * from elsewhere is named however its clients reach it.
     *
     * @param address where the server listens
     * @return the checks to make
     */
    public static ServerTransportSecurityValidator securityFor(InetAddress address) {
        if (!address.isLoopbackAddress()) {
            return ServerTransportSecurityValidator.NOOP;
        }
        DefaultServerTransportSecurityValidator.Builder local =
                DefaultServerTransportSecurityValidator.builder();
        for (String name : new String[] {"localhost", "127.0.0.1", "[::1]"}) {
            local.allowedHost(name + ":*").allowedOrigin("http://" + name + ":*");
        }
        return local.build();
    }

    /** {@return the address the endpoint answers at} */
    public URI uri() {
        InetSocketAddress bound = server.getAddress();
        String host = bound.getAddress().isAnyLocalAddress() ? "localhost"
                : bound.getAddress().getHostAddress();
        if (host.contains(":")) {
            host = "[" + host + "]";
        }
        return URI.create("http://" + host + ":" + bound.getPort() + PATH);
    }

    @Override
    public void close() {
        server.stop(0);
        executor.close();
    }

    private static void serve(HttpExchange exchange, StatelessHttpTransport transport)
            throws IOException {
        try (exchange) {
            if (!PATH.equals(exchange.getRequestURI().getPath())) {
                exchange.sendResponseHeaders(404, -1);
                return;
            }
            String body = readBody(exchange.getRequestBody());
            if (body == null) {
                // Read no further than the cap: the transport would refuse it anyway.
                exchange.sendResponseHeaders(413, -1);
                return;
            }
            StatelessHttpTransport.Response response = transport.handle(
                    exchange.getRequestMethod(), exchange.getRequestHeaders(),
                    body.isEmpty() ? null : body);
            response.headers().forEach(exchange.getResponseHeaders()::set);
            if (response.body() == null) {
                exchange.sendResponseHeaders(response.status(), -1);
                return;
            }
            byte[] bytes = response.body().getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(response.status(), bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        }
    }

    /** The body as text, or null when it is longer than the transport accepts. */
    private static String readBody(InputStream in) throws IOException {
        byte[] bytes = in.readNBytes(StatelessHttpTransport.MAX_BODY_BYTES + 1);
        return bytes.length > StatelessHttpTransport.MAX_BODY_BYTES ? null
                : new String(bytes, StandardCharsets.UTF_8);
    }
}
