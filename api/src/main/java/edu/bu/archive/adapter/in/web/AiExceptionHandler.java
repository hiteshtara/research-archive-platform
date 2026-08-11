package edu.bu.archive.adapter.in.web;

import edu.bu.archive.application.ai.AiProviderException;
import edu.bu.archive.application.ai.AiSummaryExecutionException;
import edu.bu.archive.application.ai.AwardEvidenceSearchException;

import java.time.Instant;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(assignableTypes = {
        AwardAiController.class,
        AwardAiQuestionController.class,
        AwardEvidenceSearchController.class
})
public class AiExceptionHandler {

    private static final Logger LOG =
            LoggerFactory.getLogger(AiExceptionHandler.class);

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

        LOG.error(
                "AI summary execution failed. correlationId={}",
                exception.correlationId(),
                cause
        );

        return ResponseEntity
                .status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(error(
                        HttpStatus.SERVICE_UNAVAILABLE,
                        "AI summary is temporarily unavailable",
                        exception.correlationId().toString()
                ));
    }

    @ExceptionHandler(AwardEvidenceSearchException.class)
    public ResponseEntity<Map<String, Object>> handleEvidenceSearchFailure(
            AwardEvidenceSearchException exception
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

        LOG.error(
                "Award evidence search failed. correlationId={}",
                exception.correlationId(),
                cause
        );

        return ResponseEntity
                .status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(error(
                        HttpStatus.SERVICE_UNAVAILABLE,
                        "Evidence search is temporarily unavailable",
                        exception.correlationId().toString()
                ));
    }

    @ExceptionHandler(AiProviderException.class)
    public ResponseEntity<Map<String, Object>> handleProviderFailure(
            AiProviderException exception
    ) {
        LOG.error("AI provider request failed", exception);

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
