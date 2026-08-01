package edu.bu.archive.adapter.in.web;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;

@RestControllerAdvice
public class GlobalExceptionHandler {

    /*
     * Thrown by @Validated method-parameter constraints (e.g. @Min/@Max
     * on a @RequestParam) - without this handler it falls through to
     * the default 500 response instead of a 400, even though it's
     * always a client input error.
     */
    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<Map<String, Object>> handleConstraintViolation(
            ConstraintViolationException exception,
            HttpServletRequest request
    ) {
        return ResponseEntity
                .badRequest()
                .body(
                        body(
                                HttpStatus.BAD_REQUEST,
                                "VALIDATION_ERROR",
                                exception.getMessage(),
                                request
                        )
                );
    }

    @ExceptionHandler(NoSuchElementException.class)
    public ResponseEntity<Map<String, Object>> handleNotFound(
            NoSuchElementException exception,
            HttpServletRequest request
    ) {
        return ResponseEntity
                .status(HttpStatus.NOT_FOUND)
                .body(
                        body(
                                HttpStatus.NOT_FOUND,
                                "NOT_FOUND",
                                exception.getMessage(),
                                request
                        )
                );
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> handleBadRequest(
            IllegalArgumentException exception,
            HttpServletRequest request
    ) {
        return ResponseEntity
                .badRequest()
                .body(
                        body(
                                HttpStatus.BAD_REQUEST,
                                "BAD_REQUEST",
                                exception.getMessage(),
                                request
                        )
                );
    }

    /*
     * code is a short, machine-readable string clients can switch on
     * without parsing the human-readable "error"/"message" text.
     * correlationId is generated fresh per error response - there is no
     * request-entry filter establishing one from an inbound header or
     * MDC yet app-wide (the only existing correlationId convention is
     * the AI subsystem's own, generated inside that service, unrelated
     * to HTTP request tracing) - it is a per-error support-correlation
     * id, not a propagated distributed trace id. See
     * AWARD_SEARCH_API_DESIGN.md's "Error response consistency" section.
     */
    private Map<String, Object> body(
            HttpStatus status,
            String code,
            String message,
            HttpServletRequest request
    ) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("timestamp", Instant.now().toString());
        body.put("status", status.value());
        body.put("error", status.getReasonPhrase());
        body.put("code", code);
        body.put("message", message);
        body.put("path", request.getRequestURI());
        body.put("correlationId", UUID.randomUUID().toString());
        return body;
    }
}
