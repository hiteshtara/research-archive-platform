package edu.bu.archive.application.authorization.admin.approval;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import edu.bu.archive.application.authorization.GrantType;
import edu.bu.archive.application.authorization.KualiPersonId;
import edu.bu.archive.application.authorization.admin.GrantSpecification;

/**
 * Exactly what an approval authorises, beyond its action and subject.
 *
 * <p>Every field is compared, NULL-safely, when the approval is consumed: an
 * approval for a UNIT grant on unit 100 without descendants cannot be spent on
 * unit 100 with descendants, on unit 200, or on an IO grant. Once the row
 * exists none of these can change - the V090 trigger refuses it.
 *
 * <p>{@code expiresAt} is truncated to microseconds, PostgreSQL's
 * {@code TIMESTAMPTZ} precision, so a value read back from the database
 * compares equal to the one that was written.
 *
 * @param includeDescendants a {@code Boolean} rather than a {@code boolean}
 *        because "not part of this action" (null) differs from {@code false}
 * @param cognitoIssuer LINK_PRIVILEGED only: the sign-in being given the identity
 * @param cognitoSubject LINK_PRIVILEGED only
 * @param proposedKualiPersonId LINK_PRIVILEGED only: the Kuali principal the
 *        new link will carry, bound explicitly (owner requirement). Null is a
 *        bound value too: an approval for a link with no principal cannot be
 *        spent on a link that has one, nor the reverse.
 */
public record ApprovalBinding(
        GrantType grantType,
        String unitNumber,
        String ioValue,
        String awardNumber,
        Boolean includeDescendants,
        Instant expiresAt,
        Long targetGrantId,
        String cognitoIssuer,
        String cognitoSubject,
        String proposedKualiPersonId) {

    public ApprovalBinding {
        unitNumber = trimToNull(unitNumber);
        ioValue = trimToNull(ioValue);
        awardNumber = trimToNull(awardNumber);
        cognitoIssuer = trimToNull(cognitoIssuer);
        cognitoSubject = trimToNull(cognitoSubject);
        proposedKualiPersonId = trimToNull(proposedKualiPersonId);
        expiresAt = expiresAt == null ? null : expiresAt.truncatedTo(ChronoUnit.MICROS);
    }

    /** Every binding that is not an identity link: no principal. */
    public ApprovalBinding(GrantType grantType, String unitNumber, String ioValue,
                           String awardNumber, Boolean includeDescendants, Instant expiresAt,
                           Long targetGrantId, String cognitoIssuer, String cognitoSubject) {
        this(grantType, unitNumber, ioValue, awardNumber, includeDescendants, expiresAt,
                targetGrantId, cognitoIssuer, cognitoSubject, null);
    }

    /** ADMIN_ASSIGN and ADMIN_RESTORE: the subject is the whole binding. */
    public static ApprovalBinding none() {
        return new ApprovalBinding(null, null, null, null, null, null, null, null, null);
    }

    /** SELF_GRANT and CENTRAL_GRANT: the grant exactly as it will be created. */
    public static ApprovalBinding forGrant(GrantSpecification specification) {
        return new ApprovalBinding(
                specification.type(), specification.unitNumber(), specification.ioValue(),
                specification.awardNumber(), specification.includeDescendants(),
                specification.expiresAt(), null, null, null);
    }

    /** SELF_WIDEN: the grant being changed, and the values it will be changed TO. */
    public static ApprovalBinding forWidening(long targetGrantId, boolean includeDescendants,
                                              Instant expiresAt) {
        return new ApprovalBinding(null, null, null, null, includeDescendants, expiresAt,
                targetGrantId, null, null);
    }

    /** LINK_PRIVILEGED with no Kuali principal on the new link. */
    public static ApprovalBinding forLink(String cognitoIssuer, String cognitoSubject) {
        return forLink(cognitoIssuer, cognitoSubject, null);
    }

    /**
     * LINK_PRIVILEGED: the Cognito identity that will be linked AND the Kuali
     * principal the link will carry ({@code null} for none). Both are bound.
     */
    public static ApprovalBinding forLink(String cognitoIssuer, String cognitoSubject,
                                          KualiPersonId proposedPrincipal) {
        return new ApprovalBinding(null, null, null, null, null, null, null,
                cognitoIssuer, cognitoSubject,
                proposedPrincipal == null ? null : proposedPrincipal.value());
    }

    /**
     * The Java mirror of {@code ck_pa_binding_shape}: refuse an approval that
     * is vaguer than the act it authorises, with a sentence rather than a
     * constraint name.
     */
    public void requireShapeFor(PrivilegeAction action) {
        boolean grantColumnsEmpty = grantType == null && unitNumber == null && ioValue == null
                && awardNumber == null;
        boolean linkColumnsEmpty = cognitoIssuer == null && cognitoSubject == null
                && proposedKualiPersonId == null;
        boolean ok = switch (action) {
            case ADMIN_ASSIGN, ADMIN_RESTORE -> grantColumnsEmpty && linkColumnsEmpty
                    && includeDescendants == null && expiresAt == null && targetGrantId == null;
            case SELF_GRANT -> grantType != null && includeDescendants != null
                    && targetGrantId == null && linkColumnsEmpty;
            case CENTRAL_GRANT -> grantType == GrantType.CENTRAL && unitNumber == null
                    && ioValue == null && awardNumber == null
                    && Boolean.FALSE.equals(includeDescendants)
                    && targetGrantId == null && linkColumnsEmpty;
            case SELF_WIDEN -> targetGrantId != null && includeDescendants != null
                    && grantColumnsEmpty && linkColumnsEmpty;
            case LINK_PRIVILEGED -> cognitoIssuer != null && cognitoSubject != null
                    && grantColumnsEmpty && includeDescendants == null && expiresAt == null
                    && targetGrantId == null;
        };
        if (!ok) {
            throw new IllegalArgumentException(
                    "the approval binding does not describe a " + action + " exactly");
        }
    }

    /** For audit detail. Never carries credentials; identifiers only. */
    public String describe() {
        StringBuilder out = new StringBuilder();
        append(out, "grantType", grantType);
        append(out, "unit", unitNumber);
        append(out, "io", ioValue);
        append(out, "award", awardNumber);
        append(out, "includeDescendants", includeDescendants);
        append(out, "expires", expiresAt);
        append(out, "targetGrant", targetGrantId);
        append(out, "cognitoIssuer", cognitoIssuer);
        append(out, "cognitoSubject", cognitoSubject);
        if (cognitoIssuer != null) {
            // Explicit for links, including "none": the approver must see it.
            append(out, "kualiPersonId", proposedKualiPersonId == null ? "none" : proposedKualiPersonId);
        }
        return out.length() == 0 ? "{}" : "{" + out + "}";
    }

    private static void append(StringBuilder out, String name, Object value) {
        if (value == null) {
            return;
        }
        if (out.length() > 0) {
            out.append(", ");
        }
        out.append(name).append('=').append(value);
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
