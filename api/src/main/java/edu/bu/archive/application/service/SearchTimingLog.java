package edu.bu.archive.application.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.concurrent.ThreadLocalRandom;

/*
 * TEMPORARY measurement instrumentation for QA TC-042.
 *
 * Global Search takes about 11 seconds on dev while each single-module
 * search takes between 0.4 and 1.9. Timing it from a browser can show
 * that the whole request is slow and can narrow it to a leg, but it
 * cannot separate the stages INSIDE the semantic leg - the embedding
 * call, the pgvector nearest-neighbour query, and each enrichment
 * lookup - because nothing distinguishes them from outside. Raising the
 * existing timed(...) logger would only expose the aggregate, since
 * those inner stages were never instrumented at all. This adds them.
 *
 * WHAT IT DELIBERATELY DOES NOT RECORD. No query text, no result
 * titles, no business identifiers, no embedding vectors, no
 * credentials. A timing line carries a correlation id, a stage name, a
 * duration and a count - nothing that identifies a search or a record.
 * The whole point is to be safe to turn on against a live environment
 * carrying real archive data.
 *
 * OFF BY DEFAULT. `app.search.timing.enabled` defaults to false, so
 * merging this changes nothing at runtime until someone turns it on.
 *
 * HOW TO DISABLE IT - any one of these is sufficient:
 *   1. Set app.search.timing.enabled=false (or remove the override).
 *      This is the intended switch and stops the work as well as the
 *      logging - no timestamps are taken.
 *   2. Set the logger `edu.bu.archive.search.timing` to a level above
 *      INFO. This silences the OUTPUT ONLY. The flag is still true, so
 *      every stage still takes its timestamps, computes its duration,
 *      counts its result and calls the logger - the work happens and is
 *      then discarded at the logger. Use this to quieten a noisy log,
 *      never as the way to switch the instrumentation off. Only option
 *      1 gives the zero-timestamp path.
 *   3. Delete this class and its call sites. It is temporary
 *      scaffolding for TC-042 and is expected to be removed once a
 *      target is agreed and met - see docs/QA_DECISION_RECORD.md.
 */
@Component
public class SearchTimingLog {

    /*
     * Its own logger name rather than the service's, so it can be
     * silenced or routed on its own without touching application
     * logging.
     */
    private static final Logger log =
            LoggerFactory.getLogger("edu.bu.archive.search.timing");

    private final boolean enabled;

    public SearchTimingLog(
            @Value("${app.search.timing.enabled:false}") boolean enabled
    ) {
        this.enabled = enabled;
    }

    public boolean isEnabled() {
        return enabled;
    }

    /*
     * The id for the request currently being served, set by
     * SearchRequestTimingInterceptor so the whole-request line and the
     * stages inside it share one id. The service reads it on the
     * request thread and captures it into its own lambdas before any
     * fan-out, so the worker threads never touch this.
     */
    private final ThreadLocal<String> currentCorrelationId = new ThreadLocal<>();

    public void beginRequest(String correlationId) {
        if (enabled) {
            currentCorrelationId.set(correlationId);
        }
    }

    public void endRequest() {
        currentCorrelationId.remove();
    }

    /**
     * The current request's id, or a fresh one when there is no request
     * scope - a scheduled or test call still gets coherent output
     * rather than blank ids.
     */
    public String correlationId() {
        if (!enabled) {
            return "";
        }
        String existing = currentCorrelationId.get();
        return existing != null ? existing : newCorrelationId();
    }

    /*
     * A correlation id ties the stages of one request together in the
     * log. Random, short, and never derived from the query - two
     * identical searches get different ids, and an id reveals nothing
     * about what was searched for.
     */
    public String newCorrelationId() {
        if (!enabled) {
            return "";
        }
        return Long.toHexString(ThreadLocalRandom.current().nextLong() & 0xFFFFFFFFL);
    }

    /**
     * Records one stage. {@code count} is a result or input count -
     * how many candidate rows came back, how many identifiers a lookup
     * was given - never the values themselves. Pass a negative count
     * when the stage has no meaningful count.
     */
    public void record(String correlationId, String stage, long durationMillis, int count) {
        record(correlationId, stage, durationMillis, count, true);
    }

    /**
     * Records one stage, including whether it succeeded.
     *
     * A stage that FAILED is still recorded, with its duration and
     * {@code outcome=error} - a slow failure (an embedding call that
     * retried and then gave up) is a performance finding, and dropping
     * it would hide the most interesting case. The exception's message
     * is deliberately NOT logged: it is the one field that can carry a
     * query fragment, an identifier or a connection string, and this
     * log exists to be safe against real archive data. The exception
     * itself still propagates, so the application's own error handling
     * reports it as it always did.
     */
    public void record(
            String correlationId,
            String stage,
            long durationMillis,
            int count,
            boolean succeeded
    ) {
        if (!enabled) {
            return;
        }
        String outcome = succeeded ? "ok" : "error";
        if (count < 0) {
            log.info(
                    "search-timing cid={} stage={} ms={} outcome={}",
                    correlationId, stage, durationMillis, outcome
            );
        } else {
            log.info(
                    "search-timing cid={} stage={} ms={} count={} outcome={}",
                    correlationId, stage, durationMillis, count, outcome
            );
        }
    }

    /** Times a supplier and records it, returning the supplier's value. */
    public <T> T time(
            String correlationId,
            String stage,
            java.util.function.Supplier<T> work,
            java.util.function.ToIntFunction<T> counter
    ) {
        if (!enabled) {
            return work.get();
        }
        long startNanos = System.nanoTime();
        T value = null;
        boolean succeeded = false;
        try {
            value = work.get();
            succeeded = true;
            return value;
        } finally {
            long millis = (System.nanoTime() - startNanos) / 1_000_000;
            int count = -1;
            if (value != null && counter != null) {
                try {
                    count = counter.applyAsInt(value);
                } catch (RuntimeException ignored) {
                    count = -1;
                }
            }
            record(correlationId, stage, millis, count, succeeded);
        }
    }

    /** Times a supplier with no meaningful result count. */
    public <T> T time(String correlationId, String stage, java.util.function.Supplier<T> work) {
        return time(correlationId, stage, work, null);
    }
}
