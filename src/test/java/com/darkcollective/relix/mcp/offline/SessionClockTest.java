package com.darkcollective.relix.mcp.offline;

import com.darkcollective.relix.mcp.run.QueryResult;
import com.darkcollective.relix.mcp.run.RunReport;
import com.darkcollective.relix.mcp.run.ScriptRunner;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SessionClockTest {

    @Test
    void noInstantMeansTheSystemClock() {
        assertThat(SessionClock.of(null)).isEqualTo(Clock.systemUTC());
        assertThat(SessionClock.of("  ")).isEqualTo(Clock.systemUTC());
    }

    @Test
    void anInstantFixesTheClockThere() {
        assertThat(SessionClock.of("2026-07-08T17:20:32Z").instant())
                .isEqualTo(Instant.parse("2026-07-08T17:20:32Z"));
    }

    @Test
    void aMalformedInstantSaysWhatItShouldBe() {
        assertThatThrownBy(() -> SessionClock.of("last tuesday"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(SessionClock.VARIABLE)
                .hasMessageContaining("2026-10-21T12:00:00Z");
    }

    @Test
    void aRunnerWithAFixedClockAnswersNowWithIt() {
        // A question about "the last 7 days" over data built at some instant only means
        // what it was written to mean when NOW() is that instant.
        ScriptRunner runner = new ScriptRunner(ScriptRunner.DEFAULT_SANDBOX,
                SessionClock.of("2026-07-08T17:20:32Z"));
        String script = """
                R := [
                | id | at                   |
                |----|----------------------|
                | 1  | 2026-07-05T10:00:00Z |
                | 2  | 2026-06-01T10:00:00Z |
                ];
                query { SELECT to_timestamp(at) >= NOW() - DURATION 'P7D' (R) };
                """;

        RunReport report = runner.run(script);

        assertThat(report.valid()).as(report.render()).isTrue();
        QueryResult result = report.results().getFirst();
        assertThat(result.rows()).hasSize(1);
        assertThat(result.rows().getFirst().getFirst().toString()).isEqualTo("1");
    }
}
