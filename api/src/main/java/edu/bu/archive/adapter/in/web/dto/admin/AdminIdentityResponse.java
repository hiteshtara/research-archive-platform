package edu.bu.archive.adapter.in.web.dto.admin;

import java.time.Instant;
import java.util.List;

import edu.bu.archive.application.authorization.admin.IdentityOverview;

/**
 * One identity, everything an administrator acts on.
 *
 * <p>{@code administrator} and {@code grants} are separate and are never
 * combined into a single "has access" idea. {@code noArchiveAccess} is
 * computed from grants and suspension alone, so an ARCHIVE_ADMIN with no
 * grant is reported - correctly - as having no archive access.
 */
public record AdminIdentityResponse(
        String institutionalIdentifier,
        boolean suspended,
        boolean administrator,
        boolean noArchiveAccess,
        String accessNote,
        List<AdminLinkResponse> links,
        List<AdminGrantResponse> grants,
        List<AdminAuditEntryResponse> recentAudit) {

    public static AdminIdentityResponse from(IdentityOverview overview, Instant now) {
        boolean none = overview.hasNoArchiveAccess();
        return new AdminIdentityResponse(
                overview.identity().value(),
                overview.suspended(),
                overview.administrator(),
                none,
                accessNote(overview, none),
                overview.links().stream().map(AdminLinkResponse::from).toList(),
                overview.grants().stream().map(grant -> AdminGrantResponse.from(grant, now)).toList(),
                overview.recentAudit().stream().map(AdminAuditEntryResponse::from).toList());
    }

    private static String accessNote(IdentityOverview overview, boolean none) {
        if (overview.suspended()) {
            return "Suspended: every grant is overridden and no record is visible.";
        }
        if (none && overview.administrator()) {
            return "No archive access. Holding ARCHIVE_ADMIN allows managing access "
                    + "and grants no access to records.";
        }
        if (none) {
            return "No archive access. Signing in through BU grants nothing on its own.";
        }
        return null;
    }
}
