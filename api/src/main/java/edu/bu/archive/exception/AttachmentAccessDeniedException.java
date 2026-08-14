package edu.bu.archive.exception;

/*
 * Thrown by AttachmentAuthorizationService when an authenticated caller
 * lacks the ArchiveAttachmentViewer group - mapped to 403 by
 * GlobalExceptionHandler. Distinct from an unauthenticated request,
 * which never reaches a controller method at all (401, enforced
 * globally by SecurityConfiguration before Spring MVC dispatch).
 */
public class AttachmentAccessDeniedException extends RuntimeException {
    public AttachmentAccessDeniedException(String message) {
        super(message);
    }
}
