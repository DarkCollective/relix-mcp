package com.darkcollective.relix.mcp.http;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Lets in a request whose {@value #HEADER} header holds the one key, and refuses any other
 * with 401.
 *
 * <p>The comparison takes the same time whatever the key sent: both keys are hashed, and
 * the hashes compared with {@link MessageDigest#isEqual}, so neither how much of the key
 * matched nor how long it is shows in the time a refusal takes.
 */
public final class ApiKeyAuthenticator implements Authenticator {

    /** The header a client sends its key in. */
    public static final String HEADER = "X-Api-Key";

    private static final StatelessHttpTransport.Response REFUSED = new StatelessHttpTransport.Response(
            401, Map.of("Content-Type", "text/plain; charset=utf-8"),
            "Send this server's key in the " + HEADER + " header.");

    private final byte[] key;

    /**
     * An authenticator letting in the holders of {@code key}.
     *
     * @param key the key; must not be null or blank
     */
    public ApiKeyAuthenticator(String key) {
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("an API key must not be blank");
        }
        this.key = digest(key);
    }

    @Override
    public Optional<StatelessHttpTransport.Response> refusal(Map<String, List<String>> headers) {
        return admitted(headers).isPresent() ? Optional.empty() : Optional.of(REFUSED);
    }

    /**
     * {@inheritDoc}
     *
     * <p>The SHA-256 hash of the key the request was let in with, in hex: an identity for
     * each key, from which the key cannot be read back. With one key, every caller has
     * the same owner.
     */
    @Override
    public Optional<String> owner(Map<String, List<String>> headers) {
        return admitted(headers).map(HexFormat.of()::formatHex);
    }

    /** The hash of the key the request was sent with, when it is this server's key. */
    private Optional<byte[]> admitted(Map<String, List<String>> headers) {
        List<String> sent = null;
        for (Map.Entry<String, List<String>> entry : headers.entrySet()) {
            if (HEADER.equalsIgnoreCase(entry.getKey())) {
                sent = entry.getValue();
            }
        }
        // One value, exactly: a repeated header is not a key, whichever copy matches.
        if (sent != null && sent.size() == 1 && sent.getFirst() != null) {
            byte[] digest = digest(sent.getFirst());
            if (MessageDigest.isEqual(key, digest)) {
                return Optional.of(digest);
            }
        }
        return Optional.empty();
    }

    private static byte[] digest(String text) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("every Java platform has SHA-256", e);
        }
    }
}
