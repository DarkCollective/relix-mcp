package com.darkcollective.relix.mcp;

import com.darkcollective.relix.embed.Relix;
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.transport.StdioServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema.ServerCapabilities;

/**
 * Runs the server over standard input and output, the transport a local MCP client
 * (Claude Code, Claude Desktop, an IDE) starts a server with.
 *
 * <p>Standard output belongs to the protocol, so nothing else may write to it.
 */
public final class Main {

    /** What the server tells a client it is for, before any tool is listed. */
    static final String INSTRUCTIONS = """
            Checks scripts in Relix, a relational-algebra query language, against engine \
            %s. Before showing a Relix script to anyone, pass it to 'validate' and fix what \
            it reports. To show what a script returns, 'run' it over inline tables rather \
            than predicting the rows; to show how it would run, and the SQL it would send \
            to a database, use 'explain'. Use 'learn' for the reference page of an operator, \
            function or keyword you are unsure of.""".formatted(Relix.version());

    private Main() {
    }

    /**
     * Starts the server and serves until the client closes standard input.
     *
     * @param args ignored
     */
    public static void main(String[] args) {
        McpSyncServer server = McpServer.sync(
                        new StdioServerTransportProvider(McpJsonDefaults.getMapper()))
                .serverInfo("relix", Main.class.getPackage().getImplementationVersion() == null
                        ? "dev" : Main.class.getPackage().getImplementationVersion())
                .instructions(INSTRUCTIONS)
                .capabilities(ServerCapabilities.builder().tools(false).build())
                .tools(new Tools().all())
                .build();
        Runtime.getRuntime().addShutdownHook(new Thread(server::closeGracefully));
    }
}
