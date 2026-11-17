package edu.bu.archive.application.authorization.admin.bootstrap;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import edu.bu.archive.application.authorization.ArchivePrincipalId;
import edu.bu.archive.application.authorization.InstitutionalIdentifier;
import edu.bu.archive.application.authorization.admin.AdminIdentityRepository;
import edu.bu.archive.application.authorization.admin.AdminRoleMember;
import edu.bu.archive.application.authorization.admin.AdminRoleRepository;
import edu.bu.archive.application.authorization.admin.approval.ApprovalReasons;
import edu.bu.archive.application.authorization.admin.approval.PrivilegeApprovalException;
import edu.bu.archive.application.authorization.admin.provisioning.OperatorChecks;
import edu.bu.archive.application.authorization.admin.provisioning.RuntimeIdentity;
import edu.bu.archive.application.authorization.admin.provisioning.RuntimeVerification;

/**
 * The one-time operator path that seats the SECOND ARCHIVE_ADMIN (design 11.1,
 * the option the owner approved).
 *
 * <p>Validates and plans; the writes, the lock and the authoritative refusals
 * are in {@link SecondAdministratorBootstrapService#seat}, in one transaction.
 * Every check here is repeated there under the lock, so this class can only
 * make a refusal clearer, never let something through.
 *
 * <p>Dry run by default: without {@code execute} it prints the plan and writes
 * nothing, not even an audit row. Identifiers in the output are masked.
 */
public final class SecondAdministratorBootstrapCommand {

    private final BootstrapTarget target;
    private final SecondAdministratorBootstrapService service;
    private final AdminRoleRepository adminRoles;
    private final AdminIdentityRepository identities;
    private RuntimeIdentity runtime = RuntimeIdentity.UNKNOWN;

    public SecondAdministratorBootstrapCommand(BootstrapTarget target,
                                               SecondAdministratorBootstrapService service,
                                               AdminRoleRepository adminRoles,
                                               AdminIdentityRepository identities) {
        this.target = Objects.requireNonNull(target, "target");
        this.service = Objects.requireNonNull(service, "service");
        this.adminRoles = Objects.requireNonNull(adminRoles, "adminRoles");
        this.identities = Objects.requireNonNull(identities, "identities");
    }

    /** Where this process really runs. UNKNOWN (the default) refuses an execute. */
    public SecondAdministratorBootstrapCommand withRuntime(RuntimeIdentity runtime) {
        this.runtime = Objects.requireNonNull(runtime, "runtime");
        return this;
    }

    public BootstrapOutcome run(SecondAdministratorBootstrapRequest request) {
        Objects.requireNonNull(request, "request");
        List<String> checks = new ArrayList<>();
        List<String> problems = new ArrayList<>();

        checkTarget(request, checks, problems);
        String cognitoSubject = OperatorChecks.cognitoSubject(
                request.cognitoSubject(), checks, problems);
        InstitutionalIdentifier supplied = checkIdentifier(request, checks, problems);
        checkProvenance(request, checks, problems);
        checkState(supplied, checks, problems);

        if (!problems.isEmpty()) {
            return new BootstrapOutcome(BootstrapOutcome.Result.REFUSED, checks, problems,
                    "Refused: " + problems.size() + " check(s) failed. Nothing was written.",
                    null);
        }

        if (!request.execute()) {
            checks.add("PLAN   seat ONE new ARCHIVE_ADMIN "
                    + (supplied == null ? "(identifier minted on --execute)"
                            : OperatorChecks.mask(supplied.value()))
                    + ", granted_by=" + SecondAdministratorBootstrapService.SECOND_BOOTSTRAP_ACTOR);
            checks.add("PLAN   link Cognito subject " + OperatorChecks.mask(cognitoSubject)
                    + " to it, ADMIN_VERIFIED");
            checks.add("PLAN   write audit rows ADMIN_ROLE_SECOND_BOOTSTRAPPED and "
                    + "IDENTITY_LINKED, naming the operator and the change reference");
            checks.add("PLAN   all of the above in ONE transaction; this path then refuses "
                    + "forever");
            return new BootstrapOutcome(BootstrapOutcome.Result.DRY_RUN_OK, checks, problems,
                    "Dry run: every check passed. Nothing was written. Re-run with --execute "
                            + "to seat the second administrator.",
                    null);
        }

        // Minted only here, past every check and the dry-run return.
        InstitutionalIdentifier identity = supplied != null ? supplied : ArchivePrincipalId.mint();
        SecondAdministratorBootstrapService.Result result;
        try {
            result = service.seat(identity, request.operator(), request.reason(),
                    request.changeReference(), request.cognitoIssuer(), cognitoSubject,
                    request.loginName());
        } catch (SecondAdministratorRefusedException refused) {
            problems.add(refused.getMessage());
            return new BootstrapOutcome(BootstrapOutcome.Result.REFUSED, checks, problems,
                    "Refused at execution: " + refused.getMessage() + " Nothing was written.",
                    null);
        }
        AdminRoleMember member = result.member();
        checks.add("WROTE  membership " + member.adminMemberId() + ", identity link "
                + result.link().id() + " and two audit rows, in one transaction");
        return new BootstrapOutcome(BootstrapOutcome.Result.APPLIED, checks, problems,
                "Seated the second administrator with archive identity " + identity.value()
                        + " - RECORD THIS VALUE. No archive data access was granted. This "
                        + "path will now refuse every further run.",
                member.adminMemberId());
    }

    private void checkTarget(SecondAdministratorBootstrapRequest r,
                             List<String> checks, List<String> problems) {
        compare("AWS account", OperatorChecks.trim(r.awsAccountId()), target.awsAccountId(),
                checks, problems);
        compare("environment", OperatorChecks.lower(r.environment()), target.environment(),
                checks, problems);
        RuntimeVerification.verify(runtime, OperatorChecks.trim(r.awsAccountId()),
                OperatorChecks.lower(r.environment()), r.execute(), checks, problems);
        compare("Cognito user pool", OperatorChecks.trim(r.cognitoUserPoolId()),
                target.cognitoUserPoolId(), checks, problems);
        OperatorChecks.cognitoIssuer(r.cognitoIssuer(), target.expectedIssuer(), checks, problems);
        if ("prod".equals(target.environment()) || "production".equals(target.environment())) {
            problems.add("This utility refuses to run against production. A production "
                    + "administrator needs its own approved procedure.");
        }
    }

    private static InstitutionalIdentifier checkIdentifier(SecondAdministratorBootstrapRequest r,
                                                           List<String> checks,
                                                           List<String> problems) {
        if (r.mintArchiveIdentifier()) {
            if (OperatorChecks.trim(r.institutionalIdentifier()) != null) {
                problems.add("Supply an institutional identifier OR ask for one to be minted, "
                        + "not both.");
            } else {
                checks.add("OK     an archive identifier will be MINTED on --execute");
            }
            return null;
        }
        return OperatorChecks.identifier(
                r.institutionalIdentifier(), "New administrator", checks, problems);
    }

    private static void checkProvenance(SecondAdministratorBootstrapRequest r,
                                        List<String> checks, List<String> problems) {
        if (OperatorChecks.trim(r.operator()) == null) {
            problems.add("--operator is required: the audit row records the person who ran this");
        }
        if (OperatorChecks.trim(r.changeReference()) == null) {
            problems.add("--change-reference is required, so the audit row points at the "
                    + "approved change or ticket");
        }
        try {
            ApprovalReasons.require(r.reason());
        } catch (PrivilegeApprovalException e) {
            problems.add("--reason refused: " + e.getMessage());
        }
        if (problems.isEmpty()) {
            checks.add("OK     operator, change reference and reason recorded for the audit rows");
        }
    }

    private void checkState(InstitutionalIdentifier subject,
                            List<String> checks, List<String> problems) {
        // Advisory: the service re-checks all of this under the lock.
        if (service.alreadyUsed()) {
            problems.add("The second-administrator bootstrap has already been used once. "
                    + "Appoint further administrators through the approval workflow.");
        }
        List<AdminRoleMember> active = adminRoles.findAllActive();
        if (active.size() != 1) {
            problems.add("This path runs only when EXACTLY ONE active ARCHIVE_ADMIN exists; "
                    + "found " + active.size() + ".");
        } else {
            checks.add("OK     exactly one active administrator exists");
            if (subject != null && active.get(0).member().equals(subject)) {
                problems.add("The person seated must not be the existing administrator.");
            }
        }
        if (subject != null) {
            if (identities.hasEverBeenLinked(subject)) {
                problems.add("This archive identity has been linked before. Seat a freshly "
                        + "minted identity instead.");
            }
            if (identities.isSuspended(subject)) {
                problems.add("This archive identity is suspended.");
            }
        }
    }

    private static void compare(String what, String supplied, String expected,
                                List<String> checks, List<String> problems) {
        if (!expected.equals(supplied)) {
            problems.add(what + " does not match this environment. Expected " + expected
                    + ", operator supplied " + (supplied == null ? "nothing" : supplied));
        } else {
            checks.add("OK     " + what + " matches (" + expected + ")");
        }
    }
}
