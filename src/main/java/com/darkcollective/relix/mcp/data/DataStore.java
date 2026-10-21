package com.darkcollective.relix.mcp.data;

import java.time.Duration;
import java.util.Optional;

/**
 * Where loaded data is kept between calls, under an owner and a data key.
 *
 * <p>The one place that state lives, so the tools stay functions of their arguments and
 * the transport stays stateless. An entry expires {@link #TTL} after it was last written
 * or read; a store that deletes expired entries late, as DynamoDB does, still treats them
 * as gone on every read and count.
 *
 * <p>An entry is found only under the owner it was stored under: a lookup by another
 * owner finds nothing, whatever the key.
 */
public interface DataStore {

    /** How long an entry lives after it was last used. */
    Duration TTL = Duration.ofHours(1);

    /**
     * Stores {@code data}, replacing what {@code key} held, and starts its time to live.
     *
     * @param owner whose data it is
     * @param key   the data key
     * @param data  what to hold
     */
    void put(String owner, String key, StoredData data);

    /**
     * Reads what {@code key} holds for {@code owner}, and starts its time to live again.
     *
     * @param owner whose data it is
     * @param key   the data key
     * @return the data, or empty when there is none, it has expired, or it is another
     *         owner's
     */
    Optional<StoredData> get(String owner, String key);

    /**
     * {@return how many keys {@code owner} holds that have not expired}
     *
     * @param owner whose keys to count
     */
    int active(String owner);

    /**
     * Releases {@code key} before it expires.
     *
     * @param owner whose data it is
     * @param key   the data key
     * @return whether it held anything for {@code owner}
     */
    boolean remove(String owner, String key);
}
