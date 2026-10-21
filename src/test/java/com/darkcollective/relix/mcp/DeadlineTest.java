package com.darkcollective.relix.mcp;

import com.darkcollective.relix.embed.Sandbox;
import com.darkcollective.relix.mcp.run.ScriptRunner;
import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class DeadlineTest {

    /** {@code relix_run} with no limit of the sandbox's own to reach before the deadline. */
    private final Tools tools = new Tools(new ScriptRunner(Sandbox.builder().build()));

    @Test
    void aCallInTimeIsTheToolsOwnAnswer() {
        CallToolResult result = Deadline.after(Duration.ofSeconds(30)).call(tools.runTool(),
                run("query { PROJECT Round(2.5) -> r (UNIT) };"));

        assertThat(result.isError()).isNotEqualTo(true);
        assertThat(text(result)).contains("| r NUMBER |");
    }

    @Test
    void aScriptPastTheDeadlineIsStoppedWithWhatFinishedFirst() {
        long started = System.nanoTime();
        CallToolResult result = Deadline.after(Duration.ofMillis(500)).call(tools.runTool(),
                run(SlowScript.TEXT));
        Duration took = Duration.ofNanos(System.nanoTime() - started);

        assertThat(took).isLessThan(Duration.ofMillis(500).plus(Deadline.GRACE));
        assertThat(result.isError()).isTrue();
        assertThat(text(result)).startsWith("Stopped at the 1-second limit before it finished. "
                        + "What finished first follows.")
                .contains("Query 1:\n| n NUMBER |").contains("| 10 |")
                .endsWith("Stopped before query 2 finished.")
                .doesNotContain("Query 3");
        assertThat(((Map<?, ?>) result.structuredContent()).get("stopped")).isEqualTo(true);
    }

    @Test
    void whenNothingFinishedTheAnswerSaysSo() {
        CallToolResult result = Deadline.after(Duration.ZERO).call(tools.runTool(),
                run(SlowScript.TEXT.replace("query { GROUP COUNT(*) -> n (D) };", "")));

        assertThat(result.isError()).isTrue();
        assertThat(result.content()).hasSize(1);
        assertThat(text(result)).contains("Nothing finished first");
    }

    @Test
    void workThatIgnoresTheInterruptIsAnsweredAfterTheGrace() throws InterruptedException {
        CountDownLatch release = new CountDownLatch(1);
        Tools.Definition stubborn = new Tools.Definition(Tool.builder("stubborn", Map.of()).build(),
                (request, owner, stopped) -> {
                    while (true) {
                        try {
                            if (release.await(10, TimeUnit.MILLISECONDS)) {
                                return CallToolResult.builder().addTextContent("late").build();
                            }
                        } catch (InterruptedException e) {
                            // like a solver's search, which never looks
                        }
                    }
                });

        long started = System.nanoTime();
        CallToolResult result = Deadline.after(Duration.ofMillis(100)).call(stubborn,
                CallToolRequest.builder("stubborn").build());
        Duration took = Duration.ofNanos(System.nanoTime() - started);
        release.countDown();

        assertThat(took).isGreaterThanOrEqualTo(Deadline.GRACE).isLessThan(Duration.ofSeconds(3));
        assertThat(result.isError()).isTrue();
        assertThat(text(result)).contains("Nothing finished first");
    }

    @Test
    void theDeadlineKeepsAMarginInsideThePlatformsLimit() {
        assertThat(Deadline.within(Duration.ofSeconds(60)).limit())
                .isEqualTo(Duration.ofSeconds(60).minus(Deadline.MARGIN));
        assertThat(Deadline.within(Duration.ofMillis(500)).limit()).isZero();
    }

    @Test
    void aRequestWithNoDeadlineRunsOnTheCallersThread() {
        Thread caller = Thread.currentThread();
        Tools.Definition where = new Tools.Definition(Tool.builder("where", Map.of()).build(),
                (request, owner, stopped) -> CallToolResult.builder()
                        .addTextContent(String.valueOf(Thread.currentThread() == caller)).build());

        assertThat(text(Deadline.call(where, CallToolRequest.builder("where").build(),
                McpTransportContext.EMPTY))).isEqualTo("true");
        assertThat(text(Deadline.call(where, CallToolRequest.builder("where").build(),
                Deadline.after(Duration.ofSeconds(5)).context()))).isEqualTo("false");
    }

    private static CallToolRequest run(String script) {
        return CallToolRequest.builder("relix_run").arguments(Map.of("script", script)).build();
    }

    private static String text(CallToolResult result) {
        List<String> texts = result.content().stream()
                .map(c -> ((TextContent) c).text()).toList();
        return String.join("\n", texts);
    }
}
