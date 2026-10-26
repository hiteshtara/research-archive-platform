package edu.bu.archive.adapter.in.web;

import edu.bu.archive.application.authorization.AccessNotProvisionedException;
import edu.bu.archive.application.authorization.AccessNotProvisionedProblem;
import edu.bu.archive.application.authorization.IdentityAccessDeniedException;
import edu.bu.archive.application.authorization.PartialFamilyAccessException;
import edu.bu.archive.application.authorization.ReauthenticationRequiredException;
import edu.bu.archive.exception.AttachmentAccessDeniedException;
import edu.bu.archive.exception.InvalidRequestParameterException;

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

    /*
     * Thrown by RequestParameterValidationInterceptor before any
     * controller runs. Shares the VALIDATION_ERROR code with the
     * constraint-violation handler above so a caller cannot tell - and
     * does not need to tell - which of the two refused the parameter.
     */
    @ExceptionHandler(InvalidRequestParameterException.class)
    public ResponseEntity<Map<String, Object>> handleInvalidRequestParameter(
            InvalidRequestParameterException exception,
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

    /*
     * Authenticated (already past Spring Security's 401 gate) but not a
     * member of the ArchiveAttachmentViewer group - see
     * AttachmentAuthorizationService. Deliberately its own handler
     * (not left to Spring Security's default AccessDeniedHandler) so
     * the response body matches this API's own error-body shape.
     */
    @ExceptionHandler(AttachmentAccessDeniedException.class)
    public ResponseEntity<Map<String, Object>> handleAttachmentAccessDenied(
            AttachmentAccessDeniedException exception,
            HttpServletRequest request
    ) {
        return ResponseEntity
                .status(HttpStatus.FORBIDDEN)
                .body(
                        body(
                                HttpStatus.FORBIDDEN,
                                "ATTACHMENT_ACCESS_DENIED",
                                exception.getMessage(),
                                request
                        )
                );
    }

    /*
     * --- Record authorization (Phase 2) ----------------------------------
     *
     * RecordNotAccessibleException is deliberately NOT handled here. It
     * extends NoSuchElementException and is answered 404 by the handler
     * below, so an Award the caller may not see is indistinguishable from
     * one that does not exist. A 403 would confirm the record exists, which
     * is itself a disclosure.
     *
     * The three below are about the CALLER, not about a record, so naming
     * them is safe and is what lets the UI tell a user what to do.
     */

    /** Authenticated, but no archive access has been provisioned. */
    @ExceptionHandler(AccessNotProvisionedException.class)
    public ResponseEntity<Map<String, Object>> handleAccessNotProvisioned(
            AccessNotProvisionedException exception,
            HttpServletRequest request
    ) {
        return ResponseEntity
                .status(HttpStatus.FORBIDDEN)
                .body(
                        body(
                                HttpStatus.FORBIDDEN,
                                AccessNotProvisionedProblem.CODE,
                                "No archive access has been provisioned for this account.",
                                request
                        )
                );
    }

    /** Ambiguous, revoked or suspended identity, or an evaluation failure. */
    @ExceptionHandler(IdentityAccessDeniedException.class)
    public ResponseEntity<Map<String, Object>> handleIdentityAccessDenied(
            IdentityAccessDeniedException exception,
            HttpServletRequest request
    ) {
        return ResponseEntity
                .status(HttpStatus.FORBIDDEN)
                .body(
                        body(
                                HttpStatus.FORBIDDEN,
                                "ACCESS_DENIED",
                                "Access to the archive is denied for this account.",
                                request
                        )
                );
    }

    /** The BU sign-in is too old for the configured maximum: sign in again. */
    @ExceptionHandler(ReauthenticationRequiredException.class)
    public ResponseEntity<Map<String, Object>> handleReauthenticationRequired(
            ReauthenticationRequiredException exception,
            HttpServletRequest request
    ) {
        return ResponseEntity
                .status(HttpStatus.UNAUTHORIZED)
                .body(
                        body(
                                HttpStatus.UNAUTHORIZED,
                                ReauthenticationRequiredException.CODE,
                                "Please sign in again to continue.",
                                request
                        )
                );
    }

    /**
     * A family-wide response (AI) was requested by a caller who can see some
     * but not all versions. Answered 403 rather than silently returning a
     * partial family, which would be a wrong answer presented as a complete
     * one.
     */
    @ExceptionHandler(PartialFamilyAccessException.class)
    public ResponseEntity<Map<String, Object>> handlePartialFamilyAccess(
            PartialFamilyAccessException exception,
            HttpServletRequest request
    ) {
        return ResponseEntity
                .status(HttpStatus.FORBIDDEN)
                .body(
                        body(
                                HttpStatus.FORBIDDEN,
                                PartialFamilyAccessException.CODE,
                                "This response needs access to every version of the Award.",
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
