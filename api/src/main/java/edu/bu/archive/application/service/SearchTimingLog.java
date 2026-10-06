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
 *      INFO. Silences the output while leaving the (negligible)
 *      nanoTime calls in place.
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
        if (!enabled) {
            return;
        }
        if (count < 0) {
            log.info("search-timing cid={} stage={} ms={}", correlationId, stage, durationMillis);
        } else {
            log.info(
                    "search-timing cid={} stage={} ms={} count={}",
                    correlationId, stage, durationMillis, count
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
        try {
            value = work.get();
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
            record(correlationId, stage, millis, count);
        }
    }

    /** Times a supplier with no meaningful result count. */
    public <T> T time(String correlationId, String stage, java.util.function.Supplier<T> work) {
        return time(correlationId, stage, work, null);
    }
}
