package com.darkcollective.relix.mcp.data;

import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * A {@link DataStore} in this process's memory: for standard input and output, a local
 * HTTP server, and tests. What it holds is lost when the process ends.
 */
public final class InMemoryDataStore implements DataStore {

    private record Entry(StoredData data, Instant expires) {
    }

    private final Clock clock;
    private final Map<String, Map<String, Entry>> owners = new HashMap<>();

    /**
     * A store whose entries expire by the system clock.
     */
    public InMemoryDataStore() {
        this(Clock.systemUTC());
    }

    /**
     * A store whose entries expire by {@code clock}.
     *
     * @param clock what says the time
     */
    public InMemoryDataStore(Clock clock) {
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public synchronized void put(String owner, String key, StoredData data) {
        Objects.requireNonNull(data, "data");
        keys(owner).put(key, new Entry(data, clock.instant().plus(TTL)));
    }

    @Override
    public synchronized Optional<StoredData> get(String owner, String key) {
        Map<String, Entry> keys = keys(owner);
        Entry entry = keys.get(key);
        if (entry == null) {
            return Optional.empty();
        }
        keys.put(key, new Entry(entry.data(), clock.instant().plus(TTL)));
        return Optional.of(entry.data());
    }

    @Override
    public synchronized int active(String owner) {
        return keys(owner).size();
    }

    @Override
    public synchronized boolean remove(String owner, String key) {
        return keys(owner).remove(key) != null;
    }

    /** The owner's keys, with those that have expired dropped. */
    private Map<String, Entry> keys(String owner) {
        Objects.requireNonNull(owner, "owner");
        Map<String, Entry> keys = owners.computeIfAbsent(owner, o -> new HashMap<>());
        Instant now = clock.instant();
        keys.values().removeIf(entry -> !entry.expires().isAfter(now));
        return keys;
    }
}
