package com.darkcollective.relix.mcp.http;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Decides, from its headers, whether a request may reach the server at all.
 *
 * <p>The one place a deployment says who may call it. It runs before any MCP processing,
 * so a refused request costs nothing past its headers. {@link ApiKeyAuthenticator} lets in
 * the holders of one key, and {@link #allowAll()} lets in anyone.
 */
@FunctionalInterface
public interface Authenticator {

    /**
     * Checks a request's headers.
     *
     * @param headers the request headers, by name in any case
     * @return empty if the request may proceed, or the response that refuses it
     */
    Optional<StatelessHttpTransport.Response> refusal(Map<String, List<String>> headers);

    /**
     * {@return an authenticator that refuses no one}
     */
    static Authenticator allowAll() {
        return headers -> Optional.empty();
    }
}
