package edu.bu.archive.adapter.in.web;

import edu.bu.archive.application.ai.AiProviderException;
import edu.bu.archive.application.ai.AiSummaryExecutionException;

import java.time.Instant;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(assignableTypes = AwardAiController.class)
public class AiExceptionHandler {

    @ExceptionHandler(AiSummaryExecutionException.class)
    public ResponseEntity<Map<String, Object>> handleExecutionFailure(
            AiSummaryExecutionException exception
    ) {
        Throwable cause = exception.getCause();

        if (cause instanceof java.util.NoSuchElementException) {
            return ResponseEntity
                    .status(HttpStatus.NOT_FOUND)
                    .body(error(
                            HttpStatus.NOT_FOUND,
                            cause.getMessage(),
                            exception.correlationId().toString()
                    ));
        }
        if (cause instanceof IllegalArgumentException) {
            return ResponseEntity
                    .badRequest()
                    .body(error(
                            HttpStatus.BAD_REQUEST,
                            cause.getMessage(),
                            exception.correlationId().toString()
                    ));
        }

        return ResponseEntity
                .status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(error(
                        HttpStatus.SERVICE_UNAVAILABLE,
                        "AI summary is temporarily unavailable",
                        exception.correlationId().toString()
                ));
    }

    @ExceptionHandler(AiProviderException.class)
    public ResponseEntity<Map<String, Object>> handleProviderFailure() {
        return ResponseEntity
                .status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(error(
                        HttpStatus.SERVICE_UNAVAILABLE,
                        "AI summary is temporarily unavailable",
                        null
                ));
    }

    @ExceptionHandler(java.util.NoSuchElementException.class)
    public ResponseEntity<Map<String, Object>> handleNotFound(
            java.util.NoSuchElementException exception
    ) {
        return ResponseEntity
                .status(HttpStatus.NOT_FOUND)
                .body(error(
                        HttpStatus.NOT_FOUND,
                        exception.getMessage(),
                        null
                ));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> handleBadRequest(
            IllegalArgumentException exception
    ) {
        return ResponseEntity
                .badRequest()
                .body(error(
                        HttpStatus.BAD_REQUEST,
                        exception.getMessage(),
                        null
                ));
    }

    private Map<String, Object> error(
            HttpStatus status,
            String message,
            String correlationId
    ) {
        Map<String, Object> error = new java.util.LinkedHashMap<>();
        error.put("timestamp", Instant.now().toString());
        error.put("status", status.value());
        error.put("error", status.getReasonPhrase());
        error.put("message", message);
        if (correlationId != null) {
            error.put("correlationId", correlationId);
        }
        return Map.copyOf(error);
    }
}
