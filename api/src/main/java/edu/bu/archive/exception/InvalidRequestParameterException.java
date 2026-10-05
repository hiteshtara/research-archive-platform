package edu.bu.archive.exception;

/*
 * Thrown by RequestParameterValidationInterceptor when a request
 * parameter carries a character the API refuses to accept (see
 * RequestParameterTextPolicy) - mapped to 400 VALIDATION_ERROR by
 * GlobalExceptionHandler, the same code @Validated parameter
 * constraints already produce, so every rejected search parameter
 * answers the same way.
 */
public class InvalidRequestParameterException extends RuntimeException {
    public InvalidRequestParameterException(String message) {
        super(message);
    }
}
