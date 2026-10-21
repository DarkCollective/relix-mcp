package com.darkcollective.relix.mcp;

import com.darkcollective.relix.mcp.http.Authenticator;
import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * When a tool call must have answered by: the time a request allows, kept just inside the
 * time its platform allows.
 *
 * <p>A function platform that reaches its own timeout stops the runtime: no {@code catch}
 * or {@code finally} runs, and the caller gets a generic error. So the call runs on a
 * worker thread, and when this deadline passes the worker is told to stop and interrupted.
 * The engine notices the interrupt the next time a row passes between two operators and
 * fails what it was running; the tool, told why, returns what finished first, and this
 * answers with that and says the call was stopped. Work that never passes a row between
 * operators, chiefly a solver's search, does not notice; after a short grace this answers
 * without waiting for it.
 *
 * <p>A deadline reaches the tools in the {@link McpTransportContext} of the request, under
 * {@link #KEY}. A request with none runs its tools on the thread that asked, unbounded.
 */
public final class Deadline {

    /** The key a deadline is held under in a request's transport context. */
    public static final String KEY = "relix.deadline";

    /**
     * Time kept back from the platform's limit: the grace a stopped tool has to return what
     * finished, and then the time to write the response.
     */
    public static final Duration MARGIN = Duration.ofSeconds(2);

    /** How long a stopped tool has to return what finished before the call answers without it. */
    static final Duration GRACE = Duration.ofSeconds(1);

    private final Instant end;
    private final Duration limit;

    private Deadline(Instant end, Duration limit) {
        this.end = end;
        this.limit = limit;
    }

    /**
     * The deadline for a request whose platform stops it {@code remaining} from now: that,
     * less the {@linkplain #MARGIN margin}. It follows the platform's own setting, so raising
     * that raises this.
     *
     * @param remaining how long the platform still allows
     * @return the deadline, which is now if the margin is more than remains
     */
    public static Deadline within(Duration remaining) {
        return after(remaining.minus(MARGIN));
    }

    /**
     * The deadline {@code limit} from now.
     *
     * @param limit how long a call may run; a negative one is taken as zero
     * @return the deadline
     */
    public static Deadline after(Duration limit) {
        Duration allowed = limit.isNegative() ? Duration.ZERO : limit;
        return new Deadline(Instant.now().plus(allowed), allowed);
    }

    /**
     * {@return a transport context carrying this deadline}
     */
    public McpTransportContext context() {
        return McpTransportContext.create(Map.of(KEY, this));
    }

    /**
     * {@return a transport context carrying this deadline and the request's owner}
     *
     * @param owner whose loaded data the request may read, or empty for none
     */
    public McpTransportContext context(Optional<String> owner) {
        return owner.map(o -> McpTransportContext.create(Map.of(KEY, this, Authenticator.OWNER, o)))
                .orElseGet(this::context);
    }

    /**
     * {@return how long the call was allowed, from when the deadline was set}
     */
    public Duration limit() {
        return limit;
    }

    /**
     * Answers {@code request} with {@code tool}, under the deadline {@code context} carries.
     *
     * @param tool    the tool called
     * @param request the call
     * @param context the request's transport context
     * @return the tool's result, or, if the deadline passed, what finished and that the
     *         call was stopped
     */
    static CallToolResult call(Tools.Definition tool, CallToolRequest request,
                               McpTransportContext context) {
        String owner = context != null && context.get(Authenticator.OWNER) instanceof String o
                ? o : null;
        if (context != null && context.get(KEY) instanceof Deadline deadline) {
            return deadline.call(tool, request, owner);
        }
        return tool.call().apply(request, owner, () -> false);
    }

    /**
     * Answers {@code request} with {@code tool} by this deadline.
     *
     * @param tool    the tool called
     * @param request the call
     * @return the tool's result, or, if the deadline passed, what finished and that the
     *         call was stopped
     */
    CallToolResult call(Tools.Definition tool, CallToolRequest request) {
        return call(tool, request, (String) null);
    }

    /**
     * Answers {@code request} from {@code owner} with {@code tool} by this deadline.
     *
     * @param tool    the tool called
     * @param request the call
     * @param owner   whose loaded data the call may read, or null for none
     * @return the tool's result, or, if the deadline passed, what finished and that the
     *         call was stopped
     */
    CallToolResult call(Tools.Definition tool, CallToolRequest request, String owner) {
        Objects.requireNonNull(tool, "tool");
        AtomicBoolean stopped = new AtomicBoolean();
        CompletableFuture<CallToolResult> answer = new CompletableFuture<>();
        Thread worker = Thread.ofVirtual().name("relix-call-" + tool.tool().name()).start(() -> {
            try {
                answer.complete(tool.call().apply(request, owner, stopped::get));
            } catch (Throwable t) {
                answer.completeExceptionally(t);
            }
        });
        try {
            return await(answer, Duration.between(Instant.now(), end));
        } catch (TimeoutException late) {
            // The tool reads the flag to learn why it failed, so it is set before the
            // interrupt that makes it fail.
            stopped.set(true);
            worker.interrupt();
            try {
                return stopped(await(answer, GRACE));
            } catch (TimeoutException stillRunning) {
                return stopped(null);
            }
        }
    }

    private static CallToolResult await(CompletableFuture<CallToolResult> answer, Duration wait)
            throws TimeoutException {
        try {
            return answer.get(Math.max(0, wait.toMillis()), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while waiting for a tool", e);
        } catch (ExecutionException e) {
            if (e.getCause() instanceof RuntimeException failure) {
                throw failure;
            }
            throw new IllegalStateException(e.getCause());
        }
    }

    /**
     * The answer to a call stopped at this deadline: an error saying so, then what the
     * tool returned when it stopped, if it returned in time.
     */
    private CallToolResult stopped(CallToolResult finished) {
        boolean anything = finished != null && finished.content() != null
                && !finished.content().isEmpty();
        String said = "Stopped at the " + seconds() + "-second limit before it finished. "
                + (anything ? "What finished first follows."
                : "Nothing finished first. Make the script do less work, or split it.");
        CallToolResult.Builder result = CallToolResult.builder().addTextContent(said).isError(true);
        if (anything) {
            finished.content().forEach(result::addContent);
            if (finished.structuredContent() != null) {
                result.structuredContent(finished.structuredContent());
            }
        }
        return result.build();
    }

    private long seconds() {
        return Math.max(1, Math.round(limit.toMillis() / 1000.0));
    }
}
