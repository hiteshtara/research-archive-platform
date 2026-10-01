package edu.bu.archive.application.authorization;

/**
 * What one enrollment pass decided. Every outcome except {@link #DISABLED},
 * {@link #LINK_STILL_VALID} and {@link #RETRY_DEFERRED} is recorded in
 * authz.access_audit, under the action returned by {@link #auditAction()}
 * with this code in the detail's {@code outcome} field.
 */
public enum EnrollmentOutcome {
    /** Enrollment is switched off (or enforcement is). Nothing was read or written. */
    DISABLED,
    /** An existing ACTIVE link was re-checked and still holds. Not audited. */
    LINK_STILL_VALID,
    /** A recent refusal for this identity is still cached; nothing re-read. Not audited. */
    RETRY_DEFERRED,

    LINKED,
    ALREADY_LINKED,

    REFUSED_PREVIOUSLY_REVOKED,
    REFUSED_NO_USERNAME,
    REFUSED_PROFILE_NOT_FOUND,
    REFUSED_SUBJECT_MISMATCH,
    REFUSED_PROFILE_DISABLED,
    REFUSED_NOT_FEDERATED,
    REFUSED_MISSING_IDENTIFIER,
    REFUSED_UNKNOWN_PERSON,
    REFUSED_AMBIGUOUS_MAPPING,
    REFUSED_NOT_A_KIM_PRINCIPAL,
    REFUSED_INACTIVE_PRINCIPAL,
    REFUSED_IDENTIFIER_LINKED_TO_ANOTHER_PROFILE,

    /** The profile could not be read (e.g. the user pool was unreachable). */
    FAILED_PROFILE_READ,
    /** Any other unexpected failure during enrollment. */
    FAILED,

    /** An ACTIVE AUTO_VERIFIED link whose crosswalk row or principal is no longer valid was revoked. */
    REVOKED_MAPPING_NO_LONGER_VALID,
    /**
     * On a new sign-in session the Cognito profile no longer supports the link (identifier changed,
     * e.g. a reassigned NameID now carrying another person's identifier; provider gone; sub
     * mismatch; disabled or missing profile). The link was revoked.
     */
    REVOKED_IDENTITY_EVIDENCE_CHANGED,
    /**
     * The current sign-in session could not be verified (no auth_time or username in the token,
     * or the profile read failed). The link is kept, but this request is denied.
     */
    FAILED_SESSION_VERIFICATION;

    public boolean refused() {
        return name().startsWith("REFUSED_");
    }

    public boolean failed() {
        return name().startsWith("FAILED");
    }

    /** The request must be denied even though an ACTIVE link may still exist. */
    public boolean deniesRequest() {
        return this == FAILED_SESSION_VERIFICATION;
    }

    /** The authz.access_audit action (at most 40 characters). */
    public String auditAction() {
        if (refused()) {
            return "ENROLLMENT_REFUSED";
        }
        if (failed()) {
            return "ENROLLMENT_FAILED";
        }
        return switch (this) {
            case LINKED -> "ENROLLMENT_LINKED";
            case ALREADY_LINKED -> "ENROLLMENT_ALREADY_LINKED";
            case REVOKED_MAPPING_NO_LONGER_VALID, REVOKED_IDENTITY_EVIDENCE_CHANGED -> "ENROLLMENT_LINK_REVOKED";
            default -> throw new IllegalStateException(this + " is not audited");
        };
    }
}
