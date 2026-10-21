package com.darkcollective.relix.mcp.data;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class InMemoryDataStoreTest {

    private static final StoredData DATA = new StoredData("T := [\n| a |\n|---|\n| 1 |\n];", 0, List.of());

    private final MovingClock clock = new MovingClock();
    private final InMemoryDataStore store = new InMemoryDataStore(clock);

    @Test
    void anEntryIsFoundOnlyByItsOwner() {
        store.put("alice", "k1", DATA);

        assertThat(store.get("alice", "k1")).contains(DATA);
        assertThat(store.get("bob", "k1")).isEmpty();
        assertThat(store.active("bob")).isZero();
        assertThat(store.remove("bob", "k1")).isFalse();
        assertThat(store.get("alice", "k1")).contains(DATA);
    }

    @Test
    void anEntryExpiresAnHourAfterItWasLastUsed() {
        store.put("alice", "k1", DATA);
        clock.advance(Duration.ofMinutes(50));
        assertThat(store.get("alice", "k1")).as("a read starts the hour again").isPresent();

        clock.advance(Duration.ofMinutes(50));
        assertThat(store.active("alice")).isEqualTo(1);
        assertThat(store.get("alice", "k1")).isPresent();

        clock.advance(DataStore.TTL);
        assertThat(store.active("alice")).isZero();
        assertThat(store.get("alice", "k1")).isEmpty();
    }

    @Test
    void removingAnEntryReleasesItsPlace() {
        store.put("alice", "k1", DATA);
        store.put("alice", "k2", DATA);
        assertThat(store.active("alice")).isEqualTo(2);

        assertThat(store.remove("alice", "k1")).isTrue();
        assertThat(store.remove("alice", "k1")).isFalse();
        assertThat(store.active("alice")).isEqualTo(1);
    }

    /** A clock that stands still until told to move. */
    static final class MovingClock extends Clock {

        private Instant now = Instant.parse("2026-10-21T12:00:00Z");

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
