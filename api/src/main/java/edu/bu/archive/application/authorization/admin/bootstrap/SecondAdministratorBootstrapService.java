package edu.bu.archive.application.authorization.admin.bootstrap;

import java.util.List;
import java.util.Objects;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import edu.bu.archive.application.authorization.IdentityLink;
import edu.bu.archive.application.authorization.InstitutionalIdentifier;
import edu.bu.archive.application.authorization.admin.AdminAuditAction;
import edu.bu.archive.application.authorization.admin.AdminAuditSink;
import edu.bu.archive.application.authorization.admin.AdminIdentityRepository;
import edu.bu.archive.application.authorization.admin.AdminRoleMember;
import edu.bu.archive.application.authorization.admin.AdminRoleRepository;
import edu.bu.archive.application.authorization.admin.approval.ApprovalReasons;
import edu.bu.archive.application.authorization.admin.approval.PrivilegeApprovalException;

/**
 * Seats the SECOND ARCHIVE_ADMIN, once, by an operator (design 11.1, option a).
 *
 * <p><strong>Why it exists.</strong> Once {@code ADMIN_ASSIGN} needs a second,
 * distinct, active administrator to approve it, an environment with exactly
 * one administrator can never appoint another: there is nobody to approve.
 * This path breaks that deadlock exactly once.
 *
 * <p><strong>What it is not.</strong> Not a permanent bypass: it refuses
 * unless exactly one active administrator exists, and refuses forever once it
 * has succeeded (the audit row is the record, and the audit is append-only).
 * Not self-approval: the person seated must not be the existing administrator.
 * Not a hidden superuser: the new administrator is an ordinary ARCHIVE_ADMIN
 * row with no data access, and every write is audited with the operator, the
 * change reference and the reason. And it has no HTTP path: it is reachable
 * only through {@code SecondAdministratorBootstrapCli}, behind its own profile.
 *
 * <p>The membership, the identity link and both audit rows commit in ONE
 * transaction, for the reason {@code ArchiveAdminService.bootstrapFirstAdministratorWithIdentity}
 * documents: two transactions can strand an administrator nobody can act as.
 */
@Service
public class SecondAdministratorBootstrapService {

    /** {@code granted_by}, {@code verified_by} and the audit actor for this path. */
    public static final String SECOND_BOOTSTRAP_ACTOR = "BOOTSTRAP_SECOND";

    private final AdministratorSeatStore seats;
    private final AdminRoleRepository adminRoles;
    private final AdminIdentityRepository identities;
    private final AdminAuditSink audit;

    public SecondAdministratorBootstrapService(AdministratorSeatStore seats,
                                               AdminRoleRepository adminRoles,
                                               AdminIdentityRepository identities,
                                               AdminAuditSink audit) {
        this.seats = Objects.requireNonNull(seats, "seats");
        this.adminRoles = Objects.requireNonNull(adminRoles, "adminRoles");
        this.identities = Objects.requireNonNull(identities, "identities");
        this.audit = Objects.requireNonNull(audit, "audit");
    }

    /** What one seating produced. */
    public record Result(AdminRoleMember member, IdentityLink link,
                         InstitutionalIdentifier existingAdministrator) { }

    /** Advisory: has this one-time path already been used? */
    public boolean alreadyUsed() {
        return seats.secondAdministratorEverSeated();
    }

    /**
     * Seat {@code subject} as the second ARCHIVE_ADMIN and link the Cognito
     * identity that will act as them.
     *
     * @throws SecondAdministratorRefusedException for every refusal; nothing is written
     */
    @Transactional
    public Result seat(InstitutionalIdentifier subject, String operator, String reason,
                       String changeReference, String cognitoIssuer, String cognitoSubject,
                       String loginName) {
        Objects.requireNonNull(subject, "subject");
        String who = required(operator, "the operator identity is required, so the chain of "
                + "custody starts at a recorded person");
        String ticket = required(changeReference, "a change or ticket reference is required, so "
                + "the audit row points at the approval for this seating");
        String why;
        try {
            why = ApprovalReasons.require(reason);
        } catch (PrivilegeApprovalException e) {
            throw new SecondAdministratorRefusedException("reason refused: " + e.getMessage());
        }
        String issuer = required(cognitoIssuer, "the Cognito issuer is required");
        String sub = required(cognitoSubject, "the Cognito subject is required");

        /*
         * Under the lock from here on. Every condition below is evaluated
         * against the rows as they are at the moment of the insert, and a
         * concurrent run waits for this transaction and then re-reads.
         */
        List<InstitutionalIdentifier> active = seats.lockActiveAdministrators();
        if (seats.secondAdministratorEverSeated()) {
            throw new SecondAdministratorRefusedException(
                    "the second-administrator bootstrap has already been used once. Appoint "
                            + "further administrators through the approval workflow.");
        }
        if (active.size() != 1) {
            throw new SecondAdministratorRefusedException(
                    "this path runs only when EXACTLY ONE active ARCHIVE_ADMIN exists; found "
                            + active.size() + ". "
                            + (active.isEmpty()
                                    ? "Use the first-administrator bootstrap."
                                    : "Use the approval workflow."));
        }
        InstitutionalIdentifier existing = active.get(0);
        if (existing.equals(subject)) {
            throw new SecondAdministratorRefusedException(
                    "the person seated must not be the existing administrator: that would be "
                            + "one person approving themselves.");
        }
        if (identities.isSuspended(subject)) {
            throw new SecondAdministratorRefusedException(
                    "the identity to be seated is suspended.");
        }

        String provenance = "operator=" + who + "; change=" + ticket;
        AdminRoleMember member = adminRoles.assign(subject, SECOND_BOOTSTRAP_ACTOR,
                provenance + "; " + why);
        audit.record(SECOND_BOOTSTRAP_ACTOR, AdminAuditAction.ADMIN_ROLE_SECOND_BOOTSTRAPPED,
                subject, provenance + "; existingAdministrator=" + existing.value() + "; " + why);

        IdentityLink link;
        try {
            link = identities.link(issuer, sub, subject, null, loginName, SECOND_BOOTSTRAP_ACTOR);
        } catch (IllegalStateException e) {
            // Thrown before anything commits: the whole transaction rolls back,
            // including the membership and its audit row above.
            throw new SecondAdministratorRefusedException("identity link refused: "
                    + e.getMessage());
        }
        audit.record(SECOND_BOOTSTRAP_ACTOR, AdminAuditAction.IDENTITY_LINKED, subject,
                "link=" + link.id() + "; " + provenance + "; second-administrator bootstrap");
        return new Result(member, link, existing);
    }

    private static String required(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new SecondAdministratorRefusedException(message);
        }
        return value.trim();
    }
}
