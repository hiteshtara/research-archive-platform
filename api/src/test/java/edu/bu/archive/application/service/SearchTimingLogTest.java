package edu.bu.archive.application.service;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/*
 * QA TC-042 measurement instrumentation.
 *
 * Two things are worth guarding here, and neither is "does it measure
 * time" - that part is System.nanoTime and needs no test.
 *
 *  1. It is OFF by default, and off means off: no log line, and no
 *     timing work either. Merging it must not change what a deployed
 *     API does.
 *
 *  2. It never records anything that identifies a search or a record.
 *     This is intended to be turned on against an environment holding
 *     real archive data, so "no query text, no titles, no identifiers"
 *     is the property that makes it safe, and the one that would be
 *     easiest to break later by adding a helpful-looking field.
 */
class SearchTimingLogTest {

    private static final String LOGGER_NAME = "edu.bu.archive.search.timing";

    private ListAppender<ILoggingEvent> appender;
    private ch.qos.logback.classic.Logger logger;

    @BeforeEach
    void attachAppender() {
        logger = (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(LOGGER_NAME);
        logger.setLevel(Level.INFO);
        appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
    }

    @AfterEach
    void detachAppender() {
        logger.detachAppender(appender);
        appender.stop();
    }

    private List<String> messages() {
        return appender.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
    }

    @Test
    void disabledMeansNothingIsLogged() {
        SearchTimingLog timing = new SearchTimingLog(false);

        timing.record(timing.newCorrelationId(), "SEMANTIC_EMBED", 1234, 5);
        timing.time("cid", "SEMANTIC_VECTOR_QUERY", () -> List.of(1, 2, 3), List::size);

        assertThat(messages()).isEmpty();
    }

    @Test
    void disabledStillReturnsTheWorkResult() {
        // The switch must not change behaviour, only observation.
        SearchTimingLog timing = new SearchTimingLog(false);
        assertThat(timing.time("cid", "STAGE", () -> "value")).isEqualTo("value");
        assertThat(timing.isEnabled()).isFalse();
    }

    @Test
    void disabledYieldsNoCorrelationIdToCarryAround() {
        assertThat(new SearchTimingLog(false).newCorrelationId()).isEmpty();
    }

    @Test
    void enabledRecordsStageDurationAndCount() {
        SearchTimingLog timing = new SearchTimingLog(true);

        timing.record("abc123", "SEMANTIC_EMBED", 842, 1);

        assertThat(messages()).hasSize(1);
        assertThat(messages().get(0))
                .contains("cid=abc123")
                .contains("stage=SEMANTIC_EMBED")
                .contains("ms=842")
                .contains("count=1");
    }

    @Test
    void aStageWithNoMeaningfulCountOmitsTheCountRatherThanInventingZero() {
        // Zero results and "this stage has no count" are different
        // facts; recording the second as the first would make an
        // embedding call look like it returned nothing.
        SearchTimingLog timing = new SearchTimingLog(true);

        timing.record("abc123", "SEMANTIC_EMBED", 842, -1);

        assertThat(messages().get(0)).doesNotContain("count=");
    }

    @Test
    void timingASupplierReturnsItsValueAndCountsIt() {
        SearchTimingLog timing = new SearchTimingLog(true);

        List<String> result = timing.time(
                "abc123", "SEMANTIC_VECTOR_QUERY", () -> List.of("a", "b", "c"), List::size);

        assertThat(result).containsExactly("a", "b", "c");
        assertThat(messages().get(0))
                .contains("stage=SEMANTIC_VECTOR_QUERY")
                .contains("count=3");
    }

    @Test
    void aFailedStageIsStillRecordedAndTheFailurePropagates() {
        // A stage that throws is exactly the one worth timing - a slow
        // failure (a retried, then failing, embedding call) is a
        // performance finding, and swallowing it would hide a bug.
        SearchTimingLog timing = new SearchTimingLog(true);

        try {
            timing.time("abc123", "SEMANTIC_EMBED", () -> {
                throw new IllegalStateException("bedrock unavailable");
            });
            throw new AssertionError("expected the failure to propagate");
        } catch (IllegalStateException expected) {
            assertThat(expected).hasMessage("bedrock unavailable");
        }

        assertThat(messages()).hasSize(1);
        assertThat(messages().get(0)).contains("stage=SEMANTIC_EMBED");
        // No count: there is no result to count.
        assertThat(messages().get(0)).doesNotContain("count=");
    }

    @Test
    void correlationIdsAreRandomRatherThanDerivedFromAnything() {
        // Two identical searches must not share an id, and an id must
        // not be reversible into what was searched for.
        SearchTimingLog timing = new SearchTimingLog(true);

        assertThat(timing.newCorrelationId())
                .isNotEqualTo(timing.newCorrelationId());
    }

    @Test
    void nothingIdentifyingEverReachesTheLog() {
        // The safety property. Everything passed in here is either a
        // stage name we chose or a number; the search term, the titles
        // and the identifiers are not parameters of this API at all,
        // and this asserts the recorded line carries no trace of them.
        SearchTimingLog timing = new SearchTimingLog(true);
        String cid = timing.newCorrelationId();

        timing.time(cid, "SEMANTIC_ENRICH_AWARD",
                () -> List.of("105698-00001", "100004-00002"), List::size);

        String line = messages().get(0);
        assertThat(line).contains("count=2");
        assertThat(line)
                .doesNotContain("105698")
                .doesNotContain("100004")
                .doesNotContain("autism");
    }
}
