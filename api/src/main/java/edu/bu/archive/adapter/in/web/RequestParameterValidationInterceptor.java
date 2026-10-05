package edu.bu.archive.adapter.in.web;

import edu.bu.archive.exception.InvalidRequestParameterException;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.web.servlet.HandlerInterceptor;

import java.util.Enumeration;

/*
 * Refuses a request whose parameters carry an unacceptable character,
 * before any controller method runs - one place rather than an
 * annotation repeated across every search parameter of every domain,
 * which is what kept the five search endpoints inconsistent in the
 * first place (QA TC-017).
 *
 * Deliberately a HandlerInterceptor and not a servlet Filter: an
 * exception thrown from preHandle still travels through
 * DispatcherServlet's HandlerExceptionResolver chain, so
 * GlobalExceptionHandler formats the 400 body exactly as it does for
 * every other rejected parameter. A Filter runs before the dispatcher
 * and would have to write its own body, duplicating that shape.
 */
public class RequestParameterValidationInterceptor implements HandlerInterceptor {

    @Override
    public boolean preHandle(
            HttpServletRequest request,
            HttpServletResponse response,
            Object handler
    ) {
        Enumeration<String> names = request.getParameterNames();
        while (names.hasMoreElements()) {
            String name = names.nextElement();

            reject(name, RequestParameterTextPolicy.firstDisallowed(name));

            String[] values = request.getParameterValues(name);
            if (values == null) {
                continue;
            }
            for (String value : values) {
                reject(name, RequestParameterTextPolicy.firstDisallowed(value));
            }
        }
        return true;
    }

    /*
     * The parameter name is echoed because the client chose it; the
     * value is not, only the code point of the offending character -
     * enough for a caller to find the problem without reflecting
     * unprintable input into a response or a log line.
     */
    private void reject(String name, String disallowed) {
        if (disallowed == null) {
            return;
        }
        throw new InvalidRequestParameterException(
                "Parameter '" + name + "' contains a character that is not "
                        + "allowed: " + disallowed + ". Remove it and search "
                        + "again."
        );
    }
}
