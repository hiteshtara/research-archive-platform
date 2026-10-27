package edu.bu.archive.application.authorization;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import edu.bu.archive.application.authorization.admin.GrantSpecification;

/**
 * Archive grants.
 *
 * <p>Reads are used by the per-request authorization path; writes are used
 * only by {@link edu.bu.archive.application.authorization.admin.ArchiveAdminService},
 * which is the single place administrative changes happen. The write methods
 * do not audit - the service does, in the same transaction - so calling them
 * directly would produce a permission change with no record of who made it.
 */
public interface AccessGrantRepository {

    /** Every grant (active or not) for this institutional identity. */
    List<AccessGrant> findForGrantee(InstitutionalIdentifier grantee);

    /** One grant by id, whatever its state. */
    Optional<AccessGrant> findById(long grantId);

    /**
     * Create a grant.
     *
     * <p>Per approved decision D-ADM-2 no second approver is required, so
     * {@code approvedBy} is not a parameter; the acting administrator is
     * recorded as {@code granted_by} and the audit row carries the rest.
     */
    AccessGrant create(InstitutionalIdentifier grantee, GrantSpecification specification,
                       String grantedBy);

    /**
     * Change the parts of a grant that may change: its expiry and, for UNIT
     * grants, whether it includes sub-units. Type, unit and IO value are
     * immutable - changing those is a revoke plus a new grant, so the history
     * shows what was actually authorized at each point in time.
     *
     * @throws java.util.NoSuchElementException if no active grant has that id
     */
    AccessGrant modify(long grantId, Instant expiresAt, boolean includeDescendants);

    /**
     * Revoke a grant. A status change, never a delete.
     *
     * @throws java.util.NoSuchElementException if no active grant has that id
     */
    void revoke(long grantId, String revokedBy, String reason);
}
