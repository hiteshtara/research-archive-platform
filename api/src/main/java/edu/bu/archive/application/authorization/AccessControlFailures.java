package edu.bu.archive.application.authorization;

/**
 * The record-authorization refusals that must reach {@code GlobalExceptionHandler}
 * unchanged, because each one has its own status there (403 / 401 / 409).
 *
 * <p>F3 (A8 isolated window, 2026-11-18): the AI services wrap every
 * {@link RuntimeException} into their own execution exception, which
 * {@code AiExceptionHandler} answers with 503 "temporarily unavailable". An access
 * refusal is not an outage, and reporting it as one hides the real answer from
 * the caller and from monitoring. Services call {@link #rethrowIfAccessControl}
 * at the top of such a catch block.
 *
 * <p>{@link RecordNotAccessibleException} is deliberately absent: it extends
 * {@link java.util.NoSuchElementException}, which every AI handler already maps
 * to 404, the same answer as a record that does not exist.
 */
public final class AccessControlFailures {

    private AccessControlFailures() {
    }

    public static boolean isAccessControl(Throwable failure) {
        return failure instanceof AccessNotProvisionedException
                || failure instanceof IdentityAccessDeniedException
                || failure instanceof ReauthenticationRequiredException
                || failure instanceof PartialFamilyAccessException;
    }

    /** Re-throws {@code failure} unchanged when it is an access-control refusal. */
    public static void rethrowIfAccessControl(RuntimeException failure) {
        if (isAccessControl(failure)) {
            throw failure;
        }
    }
}
