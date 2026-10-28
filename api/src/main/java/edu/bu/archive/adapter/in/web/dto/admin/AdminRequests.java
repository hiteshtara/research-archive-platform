package edu.bu.archive.adapter.in.web.dto.admin;

import java.time.Instant;

/**
 * Request bodies for the administrative API, grouped in one file because each
 * is a few fields and they are only ever used together.
 *
 * <p>None of these carries an actor: the acting administrator comes from the
 * validated token, never from request input, so a caller cannot act as someone
 * else by saying so in a body.
 */
public final class AdminRequests {

    private AdminRequests() {
    }

    /**
     * @param type CENTRAL, UNIT, IO or CONTACT_DERIVATION. IO is shown as
     *             "SAP Account" in the UI; the wire value stays IO.
     * @param expiresAt null means no automatic expiry (approved decision D-ADM-3)
     */
    public record CreateGrant(
            String type,
            String unitNumber,
            boolean includeDescendants,
            String ioValue,
            Instant expiresAt,
            String reason) {
    }

    /** Only expiry and sub-unit inclusion may change; see AccessGrantRepository#modify. */
    public record ModifyGrant(Instant expiresAt, boolean includeDescendants, String reason) {
    }

    /**
     * Link a Cognito identity an administrator has verified.
     *
     * @param kualiPersonId optional; the archive principal, when known. A
     *        principal KRIM marks inactive is accepted (decision D-ID-1).
     */
    public record CreateLink(
            String cognitoIssuer,
            String cognitoSubject,
            String kualiPersonId,
            String loginName,
            String reason) {
    }

    /** Suspend ({@code suspended=true}) or restore archive access. */
    public record Suspension(boolean suspended, String reason) {
    }

    /** Assign ARCHIVE_ADMIN to an identity. */
    public record AssignAdministrator(String institutionalIdentifier, String reason) {
    }
}
