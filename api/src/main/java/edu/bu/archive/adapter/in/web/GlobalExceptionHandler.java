package edu.bu.archive.adapter.in.web;

import jakarta.validation.ConstraintViolationException;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.Instant;
import java.util.Map;
import java.util.NoSuchElementException;

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
            ConstraintViolationException exception
    ) {
        return ResponseEntity
                .badRequest()
                .body(
                        Map.of(
                                "timestamp",
                                Instant.now().toString(),
                                "status",
                                HttpStatus.BAD_REQUEST.value(),
                                "error",
                                "Bad Request",
                                "message",
                                exception.getMessage()
                        )
                );
    }

    @ExceptionHandler(NoSuchElementException.class)
    public ResponseEntity<Map<String, Object>> handleNotFound(
            NoSuchElementException exception
    ) {
        return ResponseEntity
                .status(HttpStatus.NOT_FOUND)
                .body(
                        Map.of(
                                "timestamp",
                                Instant.now().toString(),
                                "status",
                                HttpStatus.NOT_FOUND.value(),
                                "error",
                                "Not Found",
                                "message",
                                exception.getMessage()
                        )
                );
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> handleBadRequest(
            IllegalArgumentException exception
    ) {
        return ResponseEntity
                .badRequest()
                .body(
                        Map.of(
                                "timestamp",
                                Instant.now().toString(),
                                "status",
                                HttpStatus.BAD_REQUEST.value(),
                                "error",
                                "Bad Request",
                                "message",
                                exception.getMessage()
                        )
                );
    }
}
