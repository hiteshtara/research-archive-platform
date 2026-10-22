package edu.bu.archive.application.authorization.admin;

import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import edu.bu.archive.application.authorization.InstitutionalIdentifier;

/**
 * The one place administrative changes happen.
 *
 * <p>Every method that changes anything (a) requires the caller to be an active
 * ARCHIVE_ADMIN and (b) writes an audit row in the same transaction. Both
 * checks live here rather than on controllers deliberately: the existing
 * attachment gate is enforced per endpoint across 26 controllers, which is 26
 * chances to forget it. Routing every administrative change through this
 * service makes omission impossible rather than merely discouraged.
 *
 * <p><strong>Administration is not data access.</strong> Nothing in this class
 * grants the caller or the subject any archive record access, and the
 * record-level evaluator never consults
 * {@code authz.admin_role_member}. An ARCHIVE_ADMIN with no
 * {@code authz.access_grant} row sees no records.
 *
 * <p><strong>Revocation is immediate.</strong> Authorization is resolved per
 * request from the authorization store, never from a Cognito group claim, so a
 * revoke takes effect on the next request even though the caller's JWT remains
 * valid for up to an hour.
 *
 * <p>Phase 1 scope: no administrator is bootstrapped and no live permission is
 * granted. The class is wired and tested, not used.
 */
@Service
public class ArchiveAdminService {

    private final AdminRoleRepository adminRoles;
    private final AdminAuditSink audit;

    public ArchiveAdminService(AdminRoleRepository adminRoles, AdminAuditSink audit) {
        this.adminRoles = adminRoles;
        this.audit = audit;
    }

    /** True when this identity currently holds ARCHIVE_ADMIN. */
    public boolean isAdministrator(InstitutionalIdentifier identity) {
        return identity != null && adminRoles.findActive(identity).isPresent();
    }

    /** Active administrators. Readable by an administrator only. */
    public List<AdminRoleMember> listAdministrators(InstitutionalIdentifier actor) {
        requireAdministrator(actor);
        return adminRoles.findAllActive();
    }

    /**
     * Assign ARCHIVE_ADMIN to another identity.
     *
     * <p>Only an existing active administrator may do this, so the role cannot
     * be self-granted. The first administrator arrives through
     * {@link #bootstrapFirstAdministrator} instead.
     */
    @Transactional
    public AdminRoleMember assignAdministrator(
            InstitutionalIdentifier actor, InstitutionalIdentifier subject, String reason) {

        requireAdministrator(actor);
        requireSubject(subject);

        AdminRoleMember assigned = adminRoles.assign(subject, actor.value(), reason);
        audit.record(actor.value(), AdminAuditAction.ADMIN_ROLE_ASSIGNED, subject, reason);
        return assigned;
    }

    /**
     * Revoke ARCHIVE_ADMIN.
     *
     * <p>Refused when it would leave no active administrator. The guard is
     * applied by the repository under a row lock, so two concurrent revokes of
     * the final two administrators serialise and exactly one is refused - see
     * {@link AdminRoleRepository}'s locking contract.
     *
     * <p>Self-revocation is permitted, subject to the same guard; an
     * administrator stepping down is normal, emptying the role is not.
     */
    @Transactional
    public void revokeAdministrator(
            InstitutionalIdentifier actor, InstitutionalIdentifier subject, String reason) {

        requireAdministrator(actor);
        requireSubject(subject);

        adminRoles.revokeWithLastAdministratorGuard(subject, actor.value(), reason);
        audit.record(actor.value(), AdminAuditAction.ADMIN_ROLE_REVOKED, subject, reason);
    }

    /**
     * Create the single controlled first administrator.
     *
     * <p>Not reachable through the application's own API surface: it is invoked
     * by an operator-run path that records who ran it, because the first
     * administrator cannot be granted by an existing administrator. Refuses
     * when any active administrator already exists, so it cannot be replayed to
     * inject one later.
     *
     * <p><strong>Phase 1: not invoked.</strong> No real administrator is
     * bootstrapped in this phase.
     */
    @Transactional
    public AdminRoleMember bootstrapFirstAdministrator(
            InstitutionalIdentifier subject, String operator, String reason) {

        requireSubject(subject);
        if (operator == null || operator.isBlank()) {
            throw new IllegalArgumentException(
                    "bootstrap requires the operator identity, so the chain of custody "
                            + "starts at a recorded event rather than an unexplained row");
        }

        AdminRoleMember first = adminRoles.bootstrap(subject, operator, reason);
        audit.record(AdminRoleMember.BOOTSTRAP_ACTOR,
                AdminAuditAction.ADMIN_ROLE_BOOTSTRAPPED, subject,
                "operator=" + operator + (reason == null ? "" : "; " + reason));
        return first;
    }

    /**
     * Record that an administrator changed a data-access grant.
     *
     * <p>The grant write itself belongs to the grant repository; this records
     * the administrative act. Callers must invoke it inside the same
     * transaction as the grant change so the two commit together.
     */
    @Transactional
    public void recordGrantChange(
            InstitutionalIdentifier actor, AdminAuditAction action,
            InstitutionalIdentifier subject, String detail) {

        requireAdministrator(actor);
        if (action != AdminAuditAction.ACCESS_GRANTED
                && action != AdminAuditAction.ACCESS_MODIFIED
                && action != AdminAuditAction.ACCESS_REVOKED) {
            throw new IllegalArgumentException(
                    "recordGrantChange is for ACCESS_* actions; got " + action);
        }
        audit.record(actor.value(), action, subject, detail);
    }

    private void requireAdministrator(InstitutionalIdentifier actor) {
        if (!isAdministrator(actor)) {
            throw new NotAnArchiveAdministratorException(
                    "caller does not hold an active ARCHIVE_ADMIN membership");
        }
    }

    private static void requireSubject(InstitutionalIdentifier subject) {
        if (subject == null || subject.value() == null || subject.value().isBlank()) {
            throw new IllegalArgumentException("subject identity is required");
        }
    }
}
