package com.darkcollective.relix.mcp.aws;

import com.darkcollective.relix.mcp.data.DataStore;
import com.darkcollective.relix.mcp.data.DataStoreException;
import com.darkcollective.relix.mcp.data.StoredData;
import io.modelcontextprotocol.json.McpJsonMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * A {@link DataStore} in a DynamoDB table, which every instance of a function shares.
 *
 * <p>The table is the deployment's, and its shape is this store's contract:
 * <ul>
 *   <li>{@value #OWNER} (string): the partition key, whose data it is;</li>
 *   <li>{@value #KEY} (string): the sort key, the data key;</li>
 *   <li>{@value #EXPIRES} (number): epoch seconds, the table's TTL attribute, one
 *       {@linkplain DataStore#TTL hour} after the item was last used.</li>
 * </ul>
 * The other attributes are this store's own. A lookup under another owner finds nothing,
 * because the owner is half the item's key.
 *
 * <p>DynamoDB deletes an expired item late, sometimes days late, so every read and count
 * compares {@value #EXPIRES} with the clock itself: an item past it is gone, whether or not
 * DynamoDB has deleted it yet. A read and the hour it starts again are one conditional
 * {@code UpdateItem}.
 *
 * <p>Reached through DynamoDB's JSON API, signed with {@link SigV4}, rather than the AWS
 * SDK, which would be most of the jar for four kinds of request. The role needs
 * {@code GetItem}, {@code PutItem}, {@code UpdateItem}, {@code DeleteItem} and {@code Query}
 * on the table.
 */
public final class DynamoDbDataStore implements DataStore {

    /** The variable naming the table. */
    public static final String TABLE_VARIABLE = "RELIX_MCP_DATA_TABLE";

    static final String OWNER = "owner_id";
    static final String KEY = "data_key";
    static final String EXPIRES = "expires_at";
    static final String DECLARATIONS = "declarations";
    static final String WARNINGS = "warnings";
    static final String WARNED_TABLES = "warned_tables";
    static final String READ_ONLY = "read_only";

    private final String table;
    private final URI endpoint;
    private final SigV4 signer;
    private final Supplier<Credentials> credentials;
    private final Clock clock;
    private final McpJsonMapper json;
    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(2)).build();

    /**
     * A store in {@code table}, reached at {@code endpoint}.
     *
     * @param table       the table's name
     * @param endpoint    DynamoDB's endpoint, such as {@code https://dynamodb.us-east-1.amazonaws.com}
     * @param region      the table's region
     * @param credentials what requests are signed with; a {@link Credentials.Cache} is told
     *                    when they are refused
     * @param clock       what says when an item expires
     * @param json        writes the requests and reads the answers
     */
    DynamoDbDataStore(String table, URI endpoint, String region, Supplier<Credentials> credentials,
                      Clock clock, McpJsonMapper json) {
        this.table = Objects.requireNonNull(table, "table");
        this.endpoint = Objects.requireNonNull(endpoint, "endpoint");
        this.signer = new SigV4(region, "dynamodb");
        this.credentials = Objects.requireNonNull(credentials, "credentials");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.json = Objects.requireNonNull(json, "json");
    }

    /**
     * The store the function's environment names, or empty when it names none.
     *
     * @param env  the function's environment: {@value #TABLE_VARIABLE}, and Lambda's own
     *             {@code AWS_REGION} and credentials
     * @param json writes the requests and reads the answers
     * @return the store
     * @throws IllegalStateException if a table is named but the region is not
     */
    public static Optional<DataStore> of(Map<String, String> env, McpJsonMapper json) {
        String table = env.get(TABLE_VARIABLE);
        if (table == null || table.isBlank()) {
            return Optional.empty();
        }
        String region = env.get("AWS_REGION");
        if (region == null || region.isBlank()) {
            throw new IllegalStateException(TABLE_VARIABLE + " names a table, but AWS_REGION "
                    + "says no region to find it in");
        }
        return Optional.of(new DynamoDbDataStore(table.strip(),
                URI.create("https://dynamodb." + region + ".amazonaws.com/"), region,
                new Credentials.Cache(() -> Credentials.of(env, json), Clock.systemUTC()),
                Clock.systemUTC(), json));
    }

    @Override
    public void put(String owner, String key, StoredData data) {
        Map<String, Object> item = new LinkedHashMap<>(key(owner, key));
        item.put(EXPIRES, number(later()));
        item.put(DECLARATIONS, Map.of("S", data.declarations()));
        item.put(WARNINGS, number(data.warnings()));
        item.put(WARNED_TABLES, Map.of("L", data.warnedTables().stream()
                .map(t -> Map.of("S", t)).toList()));
        item.put(READ_ONLY, Map.of("BOOL", data.readOnly()));
        call("PutItem", Map.of("TableName", table, "Item", item));
    }

    @Override
    public Optional<StoredData> get(String owner, String key) {
        Map<?, ?> answer = call("UpdateItem", Map.of(
                "TableName", table,
                "Key", key(owner, key),
                "UpdateExpression", "SET " + EXPIRES + " = :later",
                "ConditionExpression", EXPIRES + " > :now",
                "ExpressionAttributeValues", Map.of(":later", number(later()), ":now", number(now())),
                "ReturnValues", "ALL_NEW"));
        if (answer == null || !(answer.get("Attributes") instanceof Map<?, ?> item)) {
            return Optional.empty();
        }
        List<String> warned = new ArrayList<>();
        if (item.get(WARNED_TABLES) instanceof Map<?, ?> list && list.get("L") instanceof List<?> names) {
            names.forEach(n -> warned.add(String.valueOf(((Map<?, ?>) n).get("S"))));
        }
        return Optional.of(new StoredData(string(item, DECLARATIONS), (int) longOf(item, WARNINGS),
                warned, item.get(READ_ONLY) instanceof Map<?, ?> b && Boolean.TRUE.equals(b.get("BOOL"))));
    }

    @Override
    public int active(String owner) {
        int count = 0;
        Object start = null;
        do {
            Map<String, Object> request = new LinkedHashMap<>();
            request.put("TableName", table);
            request.put("KeyConditionExpression", OWNER + " = :owner");
            request.put("FilterExpression", EXPIRES + " > :now");
            request.put("ExpressionAttributeValues",
                    Map.of(":owner", Map.of("S", owner), ":now", number(now())));
            request.put("Select", "COUNT");
            if (start != null) {
                request.put("ExclusiveStartKey", start);
            }
            Map<?, ?> answer = call("Query", request);
            count += answer.get("Count") instanceof Number n ? n.intValue() : 0;
            start = answer.get("LastEvaluatedKey");
        } while (start != null);
        return count;
    }

    @Override
    public List<String> keys(String owner) {
        List<String> keys = new ArrayList<>();
        Object start = null;
        do {
            Map<String, Object> request = new LinkedHashMap<>();
            request.put("TableName", table);
            request.put("KeyConditionExpression", OWNER + " = :owner");
            request.put("FilterExpression", EXPIRES + " > :now");
            request.put("ProjectionExpression", KEY);
            request.put("ExpressionAttributeValues",
                    Map.of(":owner", Map.of("S", owner), ":now", number(now())));
            if (start != null) {
                request.put("ExclusiveStartKey", start);
            }
            Map<?, ?> answer = call("Query", request);
            if (answer.get("Items") instanceof List<?> items) {
                for (Object item : items) {
                    keys.add(string((Map<?, ?>) item, KEY));
                }
            }
            start = answer.get("LastEvaluatedKey");
        } while (start != null);
        return keys;
    }

    @Override
    public boolean remove(String owner, String key) {
        Map<?, ?> answer = call("DeleteItem", Map.of("TableName", table, "Key", key(owner, key),
                "ReturnValues", "ALL_OLD"));
        return answer != null && answer.get("Attributes") instanceof Map<?, ?> item
                && longOf(item, EXPIRES) > now();
    }

    private static Map<String, Object> key(String owner, String key) {
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(key, "key");
        return Map.of(OWNER, Map.of("S", owner), KEY, Map.of("S", key));
    }

    private long now() {
        return clock.instant().getEpochSecond();
    }

    private long later() {
        return clock.instant().plus(TTL).getEpochSecond();
    }

    private static Map<String, String> number(long n) {
        return Map.of("N", Long.toString(n));
    }

    private static String string(Map<?, ?> item, String name) {
        return item.get(name) instanceof Map<?, ?> value && value.get("S") instanceof String s ? s : "";
    }

    private static long longOf(Map<?, ?> item, String name) {
        return item.get(name) instanceof Map<?, ?> value && value.get("N") instanceof String n
                ? Long.parseLong(n) : 0;
    }

    /**
     * Sends one request to DynamoDB, signing it again with fresh credentials once if the
     * first are refused.
     *
     * @return the answer, or null when the request's condition did not hold
     */
    private Map<?, ?> call(String operation, Map<String, ?> request) {
        byte[] body;
        try {
            body = json.writeValueAsString(request).getBytes(StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new DataStoreException("cannot write a DynamoDB request", e);
        }
        for (int attempt = 1; ; attempt++) {
            Map<String, String> headers = Map.of(
                    "Content-Type", "application/x-amz-json-1.0",
                    "X-Amz-Target", "DynamoDB_20120810." + operation);
            HttpRequest.Builder builder = HttpRequest.newBuilder(endpoint)
                    .timeout(Duration.ofSeconds(5))
                    .POST(HttpRequest.BodyPublishers.ofByteArray(body));
            headers.forEach(builder::header);
            signer.sign("POST", endpoint, headers, body, credentials.get(), clock.instant())
                    .forEach(builder::header);
            HttpResponse<String> response;
            try {
                response = client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            } catch (IOException e) {
                throw new DataStoreException("cannot reach DynamoDB: " + e.getMessage(), e);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new DataStoreException("interrupted calling DynamoDB", e);
            }
            Map<?, ?> answer;
            try {
                answer = json.readValue(response.body(), Map.class);
            } catch (IOException e) {
                throw new DataStoreException("DynamoDB answered " + response.statusCode()
                        + " with what is not JSON", e);
            }
            if (response.statusCode() == 200) {
                return answer;
            }
            String type = answer.get("__type") instanceof String t ? t.substring(t.indexOf('#') + 1) : "";
            if (type.equals("ConditionalCheckFailedException")) {
                return null;
            }
            if (attempt == 1 && credentials instanceof Credentials.Cache cache
                    && (type.equals("ExpiredTokenException") || type.equals("UnrecognizedClientException")
                    || type.equals("InvalidSignatureException"))) {
                cache.forget();
                continue;
            }
            Object message = answer.get("message") != null ? answer.get("message") : answer.get("Message");
            throw new DataStoreException("DynamoDB refused " + operation + ": " + type + " "
                    + message, null);
        }
    }
}
