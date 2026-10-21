package com.darkcollective.relix.mcp.aws;

import com.darkcollective.relix.mcp.data.DataKeys;
import com.darkcollective.relix.mcp.data.DataStore;
import com.darkcollective.relix.mcp.data.LoadReport;
import com.darkcollective.relix.mcp.data.StoredData;
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.json.McpJsonMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The DynamoDB store against DynamoDB itself: DynamoDB Local, at the endpoint the
 * {@value #ENDPOINT} system property gives ({@code ./gradlew test -PdynamodbLocal=http://localhost:8000}).
 * Without it, only the tests that need no table run.
 *
 * <p>Each test makes its own table, shaped as the deployment's is: {@code owner_id} and
 * {@code data_key} as the key, {@code expires_at} the TTL attribute.
 */
class DynamoDbDataStoreTest {

    static final String ENDPOINT = "relix.mcp.dynamodbLocal";

    private static final McpJsonMapper JSON = McpJsonDefaults.getMapper();
    private static final Credentials LOCAL = new Credentials("local", "local", null, null);
    private static final StoredData DATA = new StoredData("T := [\n| a |\n|---|\n| 1 |\n];", 2,
            List.of("T"), true);

    private final MovingClock clock = new MovingClock();
    private DynamoDbDataStore store;

    @BeforeEach
    void table() throws Exception {
        String endpoint = System.getProperty(ENDPOINT, "");
        if (endpoint.isBlank()) {
            return;
        }
        String table = "relix-mcp-data-" + UUID.randomUUID();
        request(URI.create(endpoint), "CreateTable", """
                {"TableName":"%s","BillingMode":"PAY_PER_REQUEST",
                 "AttributeDefinitions":[{"AttributeName":"owner_id","AttributeType":"S"},
                                         {"AttributeName":"data_key","AttributeType":"S"}],
                 "KeySchema":[{"AttributeName":"owner_id","KeyType":"HASH"},
                              {"AttributeName":"data_key","KeyType":"RANGE"}]}""".formatted(table));
        store = new DynamoDbDataStore(table, URI.create(endpoint), "us-east-1", () -> LOCAL, clock, JSON);
    }

    @Test
    void whatIsPutIsGotBackByItsOwnerOnly() {
        assumeTrue(store != null, "no DynamoDB Local");
        store.put("alice", "k1", DATA);

        assertThat(store.get("alice", "k1")).contains(DATA);
        assertThat(store.get("bob", "k1")).isEmpty();
        assertThat(store.get("alice", "k2")).isEmpty();
        assertThat(store.remove("bob", "k1")).isFalse();
    }

    @Test
    void anItemPastItsHourIsGoneThoughDynamoDbHasNotDeletedIt() {
        assumeTrue(store != null, "no DynamoDB Local");
        store.put("alice", "k1", DATA);
        clock.advance(Duration.ofMinutes(50));
        assertThat(store.get("alice", "k1")).as("a read starts the hour again").isPresent();
        clock.advance(Duration.ofMinutes(50));
        assertThat(store.active("alice")).isEqualTo(1);

        clock.advance(DataStore.TTL);

        assertThat(store.active("alice")).isZero();
        assertThat(store.get("alice", "k1")).isEmpty();
        assertThat(store.remove("alice", "k1")).as("an expired item was not held").isFalse();
    }

    @Test
    void activeCountsOnlyTheOwnersLiveKeys() {
        assumeTrue(store != null, "no DynamoDB Local");
        store.put("alice", "k1", DATA);
        store.put("alice", "k2", DATA);
        store.put("bob", "k3", DATA);

        assertThat(store.active("alice")).isEqualTo(2);
        assertThat(store.remove("alice", "k1")).isTrue();
        assertThat(store.active("alice")).isEqualTo(1);
        assertThat(store.active("bob")).isEqualTo(1);
    }

    @Test
    void dataKeysWorkOverTheTable() {
        assumeTrue(store != null, "no DynamoDB Local");
        DataKeys keys = new DataKeys(store, 2);

        LoadReport loaded = keys.load("alice", null, "Orders := [\n| id |\n|----|\n| 1 |\n| 2 |\n];", true);

        assertThat(loaded.loaded()).as(loaded.render()).isTrue();
        assertThat(keys.find("alice", loaded.key()).orElseThrow().readOnly()).isTrue();
        assertThat(keys.load("alice", loaded.key(), null).tables())
                .extracting(LoadReport.Table::rows).containsExactly(2);
        keys.load("alice", null, "X := [\n| x |\n|---|\n| 1 |\n];");
        assertThat(keys.load("alice", null, "Y := [\n| y |\n|---|\n| 1 |\n];").render())
                .contains("already holds 2 data keys");
    }

    @Test
    void aTableIsNamedByTheEnvironmentAlongWithItsRegion() {
        assertThat(DynamoDbDataStore.of(Map.of(), JSON)).isEmpty();
        assertThat(DynamoDbDataStore.of(Map.of(DynamoDbDataStore.TABLE_VARIABLE, "t",
                "AWS_REGION", "us-east-1"), JSON)).isPresent();
        assertThatThrownBy(() -> DynamoDbDataStore.of(Map.of(DynamoDbDataStore.TABLE_VARIABLE, "t"), JSON))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("AWS_REGION");
    }

    /** Sends one signed request, as the store does, for what the store never asks. */
    private static void request(URI endpoint, String operation, String body) throws Exception {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        Map<String, String> headers = Map.of("Content-Type", "application/x-amz-json-1.0",
                "X-Amz-Target", "DynamoDB_20120810." + operation);
        HttpRequest.Builder builder = HttpRequest.newBuilder(endpoint)
                .POST(HttpRequest.BodyPublishers.ofByteArray(bytes));
        headers.forEach(builder::header);
        new SigV4("us-east-1", "dynamodb").sign("POST", endpoint, headers, bytes, LOCAL, Instant.now())
                .forEach(builder::header);
        HttpResponse<String> response = HttpClient.newHttpClient()
                .send(builder.build(), HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
    }

    /** A clock that stands still until told to move. */
    private static final class MovingClock extends Clock {

        private Instant now = Instant.now();

        void advance(Duration by) {
            now = now.plus(by);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
