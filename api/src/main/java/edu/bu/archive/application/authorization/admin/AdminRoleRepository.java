package edu.bu.archive.application.authorization.admin;

import java.util.List;
import java.util.Optional;

import edu.bu.archive.application.authorization.InstitutionalIdentifier;

/**
 * Store of ARCHIVE_ADMIN memberships.
 *
 * <p><strong>Locking contract.</strong> {@link #revokeWithLastAdministratorGuard}
 * MUST, within one transaction:
 * <ol>
 *   <li>lock the ACTIVE membership rows against concurrent modification,</li>
 *   <li>count them,</li>
 *   <li>refuse with {@link LastArchiveAdministratorException} if revoking would
 *       take the count to zero,</li>
 *   <li>otherwise perform the revoke.</li>
 * </ol>
 * Steps 1-4 cannot be split across transactions and the count cannot be taken
 * without the lock. Without the lock, two concurrent revokes of the final two
 * administrators each observe two active rows, both proceed, and the system is
 * left with none - which no CHECK constraint can prevent, because the rule is a
 * condition over the set of rows rather than over any single row.
 */
public interface AdminRoleRepository {

    /** Active membership for this identity, if any. */
    Optional<AdminRoleMember> findActive(InstitutionalIdentifier member);

    /** Every membership for this identity, newest first, active or revoked. */
    List<AdminRoleMember> findHistory(InstitutionalIdentifier member);

    /** All currently active administrators. */
    List<AdminRoleMember> findAllActive();

    /** Count of active administrators, without taking a lock. Advisory only. */
    int countActive();

    /**
     * Insert an ACTIVE membership.
     *
     * @throws IllegalStateException if an ACTIVE membership already exists
     *         (also enforced by ux_admin_role_member_active)
     */
    AdminRoleMember assign(InstitutionalIdentifier member, String grantedBy, String reason);

    /**
     * Revoke a membership under the locking contract above.
     *
     * @throws LastArchiveAdministratorException if this is the last active administrator
     * @throws IllegalStateException if the identity has no active membership
     */
    void revokeWithLastAdministratorGuard(
            InstitutionalIdentifier member, String revokedBy, String reason);

    /**
     * Insert the single controlled bootstrap membership.
     *
     * <p>Must refuse when any ACTIVE administrator already exists, so the path
     * cannot be replayed later to inject an administrator. Records
     * {@link AdminRoleMember#BOOTSTRAP_ACTOR} plus the operator identity.
     *
     * @throws IllegalStateException if an active administrator already exists
     */
    AdminRoleMember bootstrap(InstitutionalIdentifier member, String operator, String reason);
}
