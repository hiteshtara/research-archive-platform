package edu.bu.archive.application.authorization.admin;

import java.util.List;

import edu.bu.archive.application.authorization.AccessGrant;
import edu.bu.archive.application.authorization.IdentityLink;
import edu.bu.archive.application.authorization.InstitutionalIdentifier;

/**
 * Everything an administrator needs about one identity, on one screen.
 *
 * <p>{@code administrator} and {@code grants} are separate fields and are
 * never combined. Holding ARCHIVE_ADMIN confers no archive data access, so an
 * administrator with an empty {@code grants} list sees no records - the UI
 * warns on exactly that state rather than inferring access from the role.
 */
public record IdentityOverview(
        InstitutionalIdentifier identity,
        List<IdentityLink> links,
        List<AccessGrant> grants,
        boolean suspended,
        boolean administrator,
        List<AuditEntry> recentAudit) {

    /** True when nothing currently authorizes this identity to read any record. */
    public boolean hasNoArchiveAccess() {
        return suspended || grants.stream().noneMatch(grant -> grant.revokedAt() == null);
    }
}
