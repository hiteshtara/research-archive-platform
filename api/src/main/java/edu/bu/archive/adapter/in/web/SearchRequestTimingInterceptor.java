package edu.bu.archive.adapter.in.web;

import edu.bu.archive.application.service.SearchTimingLog;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.servlet.HandlerInterceptor;

/*
 * TEMPORARY measurement instrumentation for QA TC-042: the whole
 * request, not just the service call inside it.
 *
 * WHY AN INTERCEPTOR RATHER THAN A TIMER IN THE SERVICE. A timer
 * started inside GlobalSearchService.search(...) begins after the
 * request has already been routed and its parameters validated, and
 * ends before the response is serialised - so it reports the
 * orchestration and calls the rest of it "free". Spring runs this
 * interceptor's preHandle BEFORE the handler method is invoked (and so
 * before the @Validated parameter checks that run on invocation), and
 * afterCompletion AFTER the response has been written. Measuring
 * between those two points therefore covers validation, orchestration
 * and completion, which is what a reader comparing a stage against the
 * total needs it to mean.
 *
 * It also owns the correlation id, so the whole-request line and every
 * stage inside it share one id and can be read as one request.
 *
 * Records a duration, an HTTP status and that id. It does NOT record the
 * query string, the path's identifiers, headers, or any exception
 * message - see SearchTimingLog. Disabled with the same single flag.
 */
public class SearchRequestTimingInterceptor implements HandlerInterceptor {

    private static final String START_NANOS = "searchTimingStartNanos";

    private final SearchTimingLog timingLog;

    public SearchRequestTimingInterceptor(SearchTimingLog timingLog) {
        this.timingLog = timingLog;
    }

    @Override
    public boolean preHandle(
            HttpServletRequest request,
            HttpServletResponse response,
            Object handler
    ) {
        if (!timingLog.isEnabled()) {
            return true;
        }
        timingLog.beginRequest(timingLog.newCorrelationId());
        request.setAttribute(START_NANOS, System.nanoTime());
        return true;
    }

    @Override
    public void afterCompletion(
            HttpServletRequest request,
            HttpServletResponse response,
            Object handler,
            Exception exception
    ) {
        if (!timingLog.isEnabled()) {
            return;
        }
        try {
            Object start = request.getAttribute(START_NANOS);
            if (start instanceof Long startNanos) {
                long millis = (System.nanoTime() - startNanos) / 1_000_000;
                /*
                 * The HTTP status carries the outcome without carrying a
                 * message: a refused request (400) and a failed one
                 * (500) are both worth seeing in a timing run, and
                 * neither needs its text here.
                 */
                int status = response.getStatus();
                timingLog.record(
                        timingLog.correlationId(),
                        "REQUEST_TOTAL",
                        millis,
                        -1,
                        exception == null && status < 500
                );
                timingLog.record(
                        timingLog.correlationId(),
                        "REQUEST_STATUS_" + status,
                        millis,
                        -1,
                        exception == null && status < 500
                );
            }
        } finally {
            // Always clear: these threads are pooled and reused.
            timingLog.endRequest();
        }
    }
}
