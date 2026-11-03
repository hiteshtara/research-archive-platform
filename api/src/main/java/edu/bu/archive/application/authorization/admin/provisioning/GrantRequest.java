package edu.bu.archive.application.authorization.admin.provisioning;

/**
 * Everything the operator typed. Nothing here is read from a token, looked
 * up in Cognito, or inferred from an email address — the same rule the
 * bootstrap utility follows, for the same reason: an identifier that the
 * utility derives is an identifier nobody approved.
 */
public record GrantRequest(
        String awsAccountId,
        String environment,
        String granteeIdentifier,
        String actorIdentifier,
        String grantType,
        String unitNumber,
        boolean includeDescendants,
        String ioValue,
        String operator,
        String reason,
        String approvalReference,
        boolean dryRun,
        boolean confirmed,
        /**
         * {@code --expires-at}, an ISO-8601 instant, or null for a grant that
         * does not expire. Approved decision D-ADM-3: grants do not expire
         * automatically unless an administrator explicitly sets an expiry, so
         * null here is a deliberate choice and not a missing value.
         */
        String expiresAt,
        /**
         * {@code --revoke-grant-id}. When present this is a REVOCATION and
         * every create-only input is rejected, so one invocation can never be
         * both.
         */
        String revokeGrantId) {

    /** True when the operator asked to revoke rather than to create. */
    public boolean isRevocation() {
        return revokeGrantId != null && !revokeGrantId.isBlank();
    }
}
