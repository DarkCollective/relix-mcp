package com.darkcollective.relix.mcp.aws;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.stream.Collectors;

/**
 * Signs a request to an AWS service with Signature Version 4.
 *
 * <p>Written here rather than taken from the AWS SDK, as {@link ParametersAndSecrets} reads
 * the API key without it: the SDK would be most of the jar for a handful of DynamoDB
 * calls. The algorithm is AWS's published one, and {@code SigV4Test} checks it against
 * AWS's own worked example.
 */
final class SigV4 {

    private static final String ALGORITHM = "AWS4-HMAC-SHA256";
    private static final DateTimeFormatter STAMP =
            DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC);

    private final String region;
    private final String service;

    /**
     * A signer for {@code service} in {@code region}.
     *
     * @param region  the region, such as {@code us-east-1}
     * @param service the service's signing name, such as {@code dynamodb}
     */
    SigV4(String region, String service) {
        this.region = Objects.requireNonNull(region, "region");
        this.service = Objects.requireNonNull(service, "service");
    }

    /**
     * The headers that sign a request: {@code X-Amz-Date}, {@code Authorization}, and
     * {@code X-Amz-Security-Token} with temporary credentials.
     *
     * @param method      the HTTP method
     * @param uri         where the request goes; its host and path are signed
     * @param headers     the other headers the request sends, all of them signed; not
     *                    {@code Host}, which is taken from {@code uri}
     * @param body        the request body
     * @param credentials who signs
     * @param at          when the request is made
     * @return the headers to add to the request
     */
    Map<String, String> sign(String method, URI uri, Map<String, String> headers, byte[] body,
                             Credentials credentials, Instant at) {
        String stamp = STAMP.format(at);
        String day = stamp.substring(0, 8);
        Map<String, String> signed = new TreeMap<>();
        headers.forEach((name, value) -> signed.put(name.toLowerCase(Locale.ROOT), value.strip()));
        signed.put("host", uri.getPort() < 0 ? uri.getHost() : uri.getHost() + ":" + uri.getPort());
        signed.put("x-amz-date", stamp);
        if (credentials.sessionToken() != null) {
            signed.put("x-amz-security-token", credentials.sessionToken());
        }
        String names = String.join(";", signed.keySet());
        String canonical = method + "\n"
                + (uri.getRawPath() == null || uri.getRawPath().isEmpty() ? "/" : uri.getRawPath()) + "\n"
                + canonicalQuery(uri.getRawQuery()) + "\n"
                + signed.entrySet().stream().map(h -> h.getKey() + ":" + h.getValue() + "\n")
                        .collect(Collectors.joining()) + "\n"
                + names + "\n"
                + hex(sha256(body));
        String scope = day + "/" + region + "/" + service + "/aws4_request";
        String toSign = ALGORITHM + "\n" + stamp + "\n" + scope + "\n"
                + hex(sha256(canonical.getBytes(StandardCharsets.UTF_8)));
        String signature = hex(hmac(signingKey(credentials.secretAccessKey(), day), toSign));

        Map<String, String> added = new LinkedHashMap<>();
        added.put("X-Amz-Date", stamp);
        if (credentials.sessionToken() != null) {
            added.put("X-Amz-Security-Token", credentials.sessionToken());
        }
        added.put("Authorization", ALGORITHM + " Credential=" + credentials.accessKeyId() + "/"
                + scope + ", SignedHeaders=" + names + ", Signature=" + signature);
        return added;
    }

    /** The key a day's requests are signed with. */
    byte[] signingKey(String secret, String day) {
        byte[] key = hmac(("AWS4" + secret).getBytes(StandardCharsets.UTF_8), day);
        key = hmac(key, region);
        key = hmac(key, service);
        return hmac(key, "aws4_request");
    }

    /** A query string's parameters, sorted; they are taken to be encoded already. */
    private static String canonicalQuery(String query) {
        if (query == null || query.isEmpty()) {
            return "";
        }
        return Arrays.stream(query.split("&")).sorted().collect(Collectors.joining("&"));
    }

    private static byte[] hmac(byte[] key, String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("every Java platform has HmacSHA256", e);
        }
    }

    private static byte[] sha256(byte[] data) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(data);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("every Java platform has SHA-256", e);
        }
    }

    static String hex(byte[] bytes) {
        return HexFormat.of().formatHex(bytes);
    }
}
