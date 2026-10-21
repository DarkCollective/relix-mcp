package com.darkcollective.relix.mcp.http;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Decides, from its headers, whether a request may reach the server at all, and who sent
 * it.
 *
 * <p>The one place a deployment says who may call it. It runs before any MCP processing,
 * so a refused request costs nothing past its headers. {@link ApiKeyAuthenticator} lets in
 * the holders of one key, and {@link #allowAll()} lets in anyone.
 *
 * <p>A request it lets in may have an {@linkplain #owner owner}: the identity the tools
 * hold its loaded data under. The owner travels to the tools in the request's transport
 * context, under {@link #OWNER}. A request with none can load no data.
 */
@FunctionalInterface
public interface Authenticator {

    /** The key a request's owner is held under in its transport context. */
    String OWNER = "relix.owner";

    /**
     * The owner of every request to a server that only its own machine can reach: one
     * over standard input and output, or over HTTP on a loopback address.
     */
    String LOCAL = "local";

    /**
     * Checks a request's headers.
     *
     * @param headers the request headers, by name in any case
     * @return empty if the request may proceed, or the response that refuses it
     */
    Optional<StatelessHttpTransport.Response> refusal(Map<String, List<String>> headers);

    /**
     * Says who sent a request this authenticator let in.
     *
     * <p>An identity, never a credential: what the tools store and compare. Two requests
     * with the same owner share loaded data, and two with different owners never do.
     *
     * @param headers the request headers, by name in any case
     * @return the owner, or empty when this authenticator cannot tell callers apart
     */
    default Optional<String> owner(Map<String, List<String>> headers) {
        return Optional.empty();
    }

    /**
     * {@return an authenticator that refuses no one}
     */
    static Authenticator allowAll() {
        return headers -> Optional.empty();
    }
}
