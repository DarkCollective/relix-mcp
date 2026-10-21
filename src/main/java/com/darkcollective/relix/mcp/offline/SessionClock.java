package com.darkcollective.relix.mcp.offline;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;

/**
 * The clock every session's {@code NOW()} reads: the system's, unless the server is
 * started with {@value #VARIABLE} set to an ISO-8601 instant, which fixes it there.
 *
 * <p>For tests and evaluations, never production: a question about "the last 7 days"
 * over data built relative to some instant only means what its author meant when
 * {@code NOW()} is that instant. The engine supports it as public API
 * ({@code Relix.Builder.clock}); this only chooses the clock. Nothing a tool is sent can
 * change it.
 */
public final class SessionClock {

    /** The environment variable that fixes {@code NOW()}, e.g. {@code 2026-10-21T12:00:00Z}. */
    public static final String VARIABLE = "RELIX_MCP_NOW";

    private SessionClock() {
    }

    /**
     * {@return the clock the server's environment names}
     *
     * @throws IllegalArgumentException when {@value #VARIABLE} is set but is not an instant
     */
    public static Clock fromEnvironment() {
        return of(System.getenv(VARIABLE));
    }

    /**
     * {@return a clock fixed at an instant, or the system's when none is given}
     *
     * @param instant an ISO-8601 instant such as {@code 2026-10-21T12:00:00Z}; null or
     *                blank for the system clock
     * @throws IllegalArgumentException when the text is not an instant
     */
    public static Clock of(String instant) {
        if (instant == null || instant.isBlank()) {
            return Clock.systemUTC();
        }
        try {
            return Clock.fixed(Instant.parse(instant.strip()), ZoneOffset.UTC);
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException(VARIABLE + " must be an ISO-8601 instant such as "
                    + "2026-10-21T12:00:00Z, not '" + instant + "'", e);
        }
    }
}
