package edu.bu.archive.adapter.in.web;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import edu.bu.archive.application.service.SearchTimingLog;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/*
 * QA TC-042. REQUEST_TOTAL has to mean the WHOLE request, or a stage
 * compared against it reads as a smaller share of the cost than it
 * really is.
 *
 * A timer inside GlobalSearchService cannot deliver that: it starts
 * after routing and parameter validation and stops before the response
 * is written. This interceptor's preHandle runs before the handler is
 * invoked - and so before the @Validated parameter checks that run on
 * invocation - and its afterCompletion runs after the response has been
 * written. These tests pin that window, including the case that proves
 * it: a request REFUSED during validation never reaches the service, so
 * only an interceptor can time it at all.
 */
class SearchRequestTimingInterceptorTest {

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

    private static MockHttpServletRequest request() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/global-search");
        request.setQueryString("query=autism");
        return request;
    }

    @Test
    void disabledRecordsNothingAndStoresNoRequestState() {
        SearchTimingLog timing = new SearchTimingLog(false);
        SearchRequestTimingInterceptor interceptor = new SearchRequestTimingInterceptor(timing);
        MockHttpServletRequest request = request();

        interceptor.preHandle(request, new MockHttpServletResponse(), new Object());
        interceptor.afterCompletion(request, new MockHttpServletResponse(), new Object(), null);

        assertThat(messages()).isEmpty();
        assertThat(request.getAttribute("searchTimingStartNanos")).isNull();
    }

    @Test
    void aSuccessfulRequestIsRecordedWithItsStatus() {
        SearchTimingLog timing = new SearchTimingLog(true);
        SearchRequestTimingInterceptor interceptor = new SearchRequestTimingInterceptor(timing);
        MockHttpServletRequest request = request();
        MockHttpServletResponse response = new MockHttpServletResponse();
        response.setStatus(200);

        interceptor.preHandle(request, response, new Object());
        interceptor.afterCompletion(request, response, new Object(), null);

        assertThat(messages()).anyMatch(m -> m.contains("stage=REQUEST_TOTAL"));
        assertThat(messages()).anyMatch(m -> m.contains("stage=REQUEST_STATUS_200"));
        assertThat(messages()).allMatch(m -> m.contains("outcome=ok"));
    }

    @Test
    void aRequestRefusedDuringValidationIsStillTimed() {
        // The decisive case for measuring here rather than in the
        // service. A 400 means the handler's parameter validation
        // rejected it, so GlobalSearchService never ran - a timer
        // inside the service would report nothing at all for a request
        // that nonetheless took real time.
        SearchTimingLog timing = new SearchTimingLog(true);
        SearchRequestTimingInterceptor interceptor = new SearchRequestTimingInterceptor(timing);
        MockHttpServletRequest request = request();
        MockHttpServletResponse response = new MockHttpServletResponse();

        interceptor.preHandle(request, response, new Object());
        response.setStatus(400);
        interceptor.afterCompletion(request, response, new Object(), null);

        assertThat(messages()).anyMatch(m -> m.contains("stage=REQUEST_TOTAL"));
        assertThat(messages()).anyMatch(m -> m.contains("stage=REQUEST_STATUS_400"));
        // A refusal is a correct outcome, not a failure of the service.
        assertThat(messages()).allMatch(m -> m.contains("outcome=ok"));
    }

    @Test
    void aFailedRequestIsRecordedAsAnErrorWithoutItsMessage() {
        SearchTimingLog timing = new SearchTimingLog(true);
        SearchRequestTimingInterceptor interceptor = new SearchRequestTimingInterceptor(timing);
        MockHttpServletRequest request = request();
        MockHttpServletResponse response = new MockHttpServletResponse();

        interceptor.preHandle(request, response, new Object());
        response.setStatus(500);
        interceptor.afterCompletion(
                request, response, new Object(),
                new IllegalStateException("pgvector connection to db-prod:5432 failed"));

        assertThat(messages()).anyMatch(m -> m.contains("outcome=error"));
        assertThat(messages()).noneMatch(m -> m.contains("db-prod"));
        assertThat(messages()).noneMatch(m -> m.contains("pgvector connection"));
    }

    @Test
    void theQueryStringNeverReachesTheLog() {
        SearchTimingLog timing = new SearchTimingLog(true);
        SearchRequestTimingInterceptor interceptor = new SearchRequestTimingInterceptor(timing);
        MockHttpServletRequest request = request();
        MockHttpServletResponse response = new MockHttpServletResponse();
        response.setStatus(200);

        interceptor.preHandle(request, response, new Object());
        interceptor.afterCompletion(request, response, new Object(), null);

        assertThat(messages()).noneMatch(m -> m.contains("autism"));
        assertThat(messages()).noneMatch(m -> m.contains("query="));
    }

    @Test
    void theCorrelationIdIsClearedSoPooledThreadsDoNotInheritIt() {
        // Servlet threads are reused. A leaked id would stitch two
        // unrelated requests together in the log and quietly corrupt
        // the measurement.
        SearchTimingLog timing = new SearchTimingLog(true);
        SearchRequestTimingInterceptor interceptor = new SearchRequestTimingInterceptor(timing);
        MockHttpServletRequest request = request();
        MockHttpServletResponse response = new MockHttpServletResponse();
        response.setStatus(200);

        interceptor.preHandle(request, response, new Object());
        String during = timing.correlationId();
        interceptor.afterCompletion(request, response, new Object(), null);
        String after = timing.correlationId();

        assertThat(during).isNotEmpty();
        assertThat(after).isNotEqualTo(during);
    }

    @Test
    void afterCompletionWithoutPreHandleRecordsNothing() {
        // Defensive: an interceptor earlier in the chain can abort the
        // request before this one's preHandle runs.
        SearchTimingLog timing = new SearchTimingLog(true);
        SearchRequestTimingInterceptor interceptor = new SearchRequestTimingInterceptor(timing);

        interceptor.afterCompletion(
                request(), new MockHttpServletResponse(), new Object(), null);

        assertThat(messages()).isEmpty();
    }
}
