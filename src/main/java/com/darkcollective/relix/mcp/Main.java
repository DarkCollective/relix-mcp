package com.darkcollective.relix.mcp;

import com.darkcollective.relix.embed.Relix;
import com.darkcollective.relix.mcp.http.LocalHttpServer;
import com.darkcollective.relix.mcp.http.StatelessHttpTransport;
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.McpStatelessServerFeatures;
import io.modelcontextprotocol.server.McpStatelessSyncServer;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.transport.StdioServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema.ServerCapabilities;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.List;

/**
 * Runs the server.
 *
 * <p>With no arguments it speaks over standard input and output, the transport a local MCP
 * client (Claude Code, Claude Desktop, an IDE) starts a server with; standard output then
 * belongs to the protocol, so nothing else may write to it. With {@code --http} it serves
 * MCP's Streamable HTTP transport instead, statelessly, at {@code /mcp}.
 */
public final class Main {

    /** What the server tells a client it is for, before any tool is listed. */
    static final String INSTRUCTIONS = """
            Checks scripts in Relix, a relational-algebra query language, against engine \
            %s. Before showing a Relix script to anyone, pass it to 'validate' and fix what \
            it reports. To show what a script returns, 'run' it over inline tables rather \
            than predicting the rows; to show how it would run, and the SQL it would send \
            to a database, use 'explain'. Use 'learn' for the reference page of an operator, \
            function or keyword you are unsure of, and learn '%s' for the whole grammar. \
            The same material is published at %s and %s.""".formatted(Relix.version(),
            Tools.GRAMMAR_PAGE, Tools.LLMS_TXT, Tools.GRAMMAR_TXT);

    static final String USAGE = """
            usage: relix-mcp                 serve MCP over standard input and output
                   relix-mcp --http [--host H] [--port N]
                                             serve MCP over HTTP at http://H:N/mcp
                                             (default host 127.0.0.1, port 8080)""";

    private Main() {
    }

    /**
     * Starts the server and serves until the client closes standard input, or, over HTTP,
     * until the process is stopped.
     *
     * @param args nothing, or {@code --http} with an optional {@code --host} and {@code --port}
     * @throws IOException if the HTTP address cannot be bound
     */
    public static void main(String[] args) throws IOException {
        Options options;
        try {
            options = Options.parse(List.of(args));
        } catch (IllegalArgumentException e) {
            System.err.println("relix-mcp: " + e.getMessage());
            System.err.println(USAGE);
            System.exit(2);
            return;
        }
        if (options.http()) {
            LocalHttpServer server = serveHttp(new InetSocketAddress(options.host(), options.port()));
            System.err.println("relix-mcp " + version() + " serving MCP at " + server.uri());
            Runtime.getRuntime().addShutdownHook(new Thread(server::close));
        } else {
            McpSyncServer server = McpServer.sync(
                            new StdioServerTransportProvider(McpJsonDefaults.getMapper()))
                    .serverInfo("relix", version())
                    .instructions(INSTRUCTIONS)
                    .capabilities(ServerCapabilities.builder().tools(false).build())
                    .tools(new Tools().all().stream().map(tool ->
                            new McpServerFeatures.SyncToolSpecification(tool.tool(),
                                    (exchange, request) -> tool.call().apply(request))).toList())
                    .build();
            Runtime.getRuntime().addShutdownHook(new Thread(server::closeGracefully));
        }
    }

    /**
     * Serves the tools over HTTP at {@code address}, checking request headers as
     * {@link LocalHttpServer#securityFor} says.
     *
     * @param address where to listen; port 0 picks a free one
     * @return the running server, which the caller closes
     * @throws IOException if the address cannot be bound
     */
    static LocalHttpServer serveHttp(InetSocketAddress address) throws IOException {
        StatelessHttpTransport transport = new StatelessHttpTransport(McpJsonDefaults.getMapper(),
                LocalHttpServer.securityFor(address.getAddress()));
        statelessServer(transport);
        return LocalHttpServer.start(address, transport);
    }

    /**
     * The tools, served statelessly over {@code transport}: every call is answered from
     * what it carries, so any number of instances can answer any request.
     *
     * @param transport carries the requests; the server registers itself as its handler
     * @return the server
     */
    static McpStatelessSyncServer statelessServer(StatelessHttpTransport transport) {
        return McpServer.sync(transport)
                .serverInfo("relix", version())
                .instructions(INSTRUCTIONS)
                .capabilities(ServerCapabilities.builder().tools(false).build())
                .tools(new Tools().all().stream().map(tool ->
                        new McpStatelessServerFeatures.SyncToolSpecification(tool.tool(),
                                (context, request) -> tool.call().apply(request))).toList())
                .build();
    }

    private static String version() {
        String version = Main.class.getPackage().getImplementationVersion();
        return version == null ? "dev" : version;
    }

    /**
     * The command line, read.
     *
     * @param http whether to serve over HTTP rather than standard input and output
     * @param host the address to listen on, over HTTP
     * @param port the port to listen on, over HTTP; 0 picks a free one
     */
    record Options(boolean http, InetAddress host, int port) {

        static Options parse(List<String> args) {
            boolean http = false;
            String host = "127.0.0.1";
            int port = 8080;
            for (int i = 0; i < args.size(); i++) {
                String arg = args.get(i);
                switch (arg) {
                    case "--http" -> http = true;
                    case "--host", "--port" -> {
                        if (i + 1 == args.size()) {
                            throw new IllegalArgumentException(arg + " needs a value");
                        }
                        String value = args.get(++i);
                        if (arg.equals("--host")) {
                            host = value;
                        } else {
                            port = port(value);
                        }
                    }
                    default -> throw new IllegalArgumentException("unknown argument '" + arg + "'");
                }
            }
            if (!http && (args.contains("--host") || args.contains("--port"))) {
                throw new IllegalArgumentException("--host and --port apply only with --http");
            }
            try {
                return new Options(http, InetAddress.getByName(host), port);
            } catch (IOException e) {
                throw new IllegalArgumentException("cannot resolve host '" + host + "'");
            }
        }

        private static int port(String value) {
            try {
                int port = Integer.parseInt(value);
                if (port >= 0 && port <= 65535) {
                    return port;
                }
            } catch (NumberFormatException e) {
                // reported below, as an out-of-range port is
            }
            throw new IllegalArgumentException("--port takes a number from 0 to 65535, not '"
                    + value + "'");
        }
    }
}
