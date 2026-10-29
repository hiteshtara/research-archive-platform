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
        boolean confirmed) { }
