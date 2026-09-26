package com.darkcollective.relix.mcp;

import com.darkcollective.relix.mcp.http.StatelessHttpTransport;
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.server.transport.ServerTransportSecurityValidator;

/** The server's tools behind a transport, for tests outside this package. */
public final class HttpServers {

    private HttpServers() {
    }

    /**
     * {@return a transport answering with every tool, checking headers with {@code security}}
     *
     * @param security the header checks to make
     */
    public static StatelessHttpTransport withTools(ServerTransportSecurityValidator security) {
        StatelessHttpTransport transport =
                new StatelessHttpTransport(McpJsonDefaults.getMapper(), security);
        Main.statelessServer(transport);
        return transport;
    }
}
