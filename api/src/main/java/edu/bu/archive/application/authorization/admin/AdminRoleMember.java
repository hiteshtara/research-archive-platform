package edu.bu.archive.application.authorization.admin;

import java.time.Instant;

import edu.bu.archive.application.authorization.InstitutionalIdentifier;

/**
 * One ARCHIVE_ADMIN membership row (authz.admin_role_member).
 *
 * <p>Administrative membership is deliberately NOT an
 * {@link edu.bu.archive.application.authorization.AccessGrant}: holding
 * ARCHIVE_ADMIN confers the ability to administer access and confers no archive
 * data access at all. Keeping the two types distinct means a future query
 * cannot accidentally treat an administrator as a data-authorized user.
 */
public record AdminRoleMember(
        long adminMemberId,
        InstitutionalIdentifier member,
        AdminRoleMemberStatus status,
        String grantedBy,
        Instant grantedAt,
        String revokedBy,
        Instant revokedAt,
        String reason) {

    /** Literal recorded as {@code grantedBy} for the single controlled bootstrap grant. */
    public static final String BOOTSTRAP_ACTOR = "BOOTSTRAP";

    public boolean isActive() {
        return status == AdminRoleMemberStatus.ACTIVE;
    }
}
