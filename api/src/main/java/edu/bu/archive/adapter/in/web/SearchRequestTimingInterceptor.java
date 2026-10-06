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
 * afterCompletion AFTER the response has been written.
 *
 * WHAT IT IS CALLED, AND WHY NOT "REQUEST_TOTAL". The stage is
 * MVC_REQUEST_TOTAL, because that is honestly all it covers: the
 * DispatcherServlet's handling of the request. It EXCLUDES anything
 * earlier in the chain - servlet filters, Spring Security, CORS
 * handling, TLS termination, the load balancer - and it cannot see a
 * failure that happens before a handler is selected, since preHandle
 * never runs for one. Calling it the total would overstate it, and
 * would make every stage inside look like a larger share of the real
 * cost than it is.
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
                 * Status and outcome are recorded as SEPARATE fields,
                 * never derived from one another. A handled error
                 * reaches here with exception == null - an
                 * @ExceptionHandler turned it into a 500 and the
                 * dispatch completed normally - so a status-derived
                 * outcome would call that a success, and an
                 * exception-derived status would miss it entirely.
                 * Neither the status nor the outcome carries any
                 * message.
                 */
                timingLog.recordDispatch(
                        timingLog.correlationId(),
                        "MVC_REQUEST_TOTAL",
                        millis,
                        response.getStatus(),
                        exception != null
                );
            }
        } finally {
            // Always clear: these threads are pooled and reused.
            timingLog.endRequest();
        }
    }
}
