package edu.bu.archive.application.authorization.admin.bootstrap;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

import edu.bu.archive.application.authorization.ArchivePrincipalId;
import edu.bu.archive.application.authorization.AuthorizationProperties;
import edu.bu.archive.application.authorization.InstitutionalIdentifier;
import edu.bu.archive.application.authorization.KualiPersonId;
import edu.bu.archive.application.authorization.admin.AdminIdentityRepository;
import edu.bu.archive.application.authorization.admin.AdminRoleMember;
import edu.bu.archive.application.authorization.admin.AdminRoleRepository;
import edu.bu.archive.application.authorization.admin.ArchiveAdminService;

/**
 * The one-time operator path that creates the first ARCHIVE_ADMIN.
 *
 * <p>It exists because the first administrator cannot be appointed by an
 * existing one, and there is deliberately no HTTP endpoint: a web route to
 * this would be a privilege-escalation path around the entire model. So it is
 * reachable only by someone who can run a process against the database.
 *
 * <p><strong>This class validates and sequences; it does not implement the
 * rules.</strong> The membership and its audit row are written by
 * {@link ArchiveAdminService#bootstrapFirstAdministrator}, which is
 * {@code @Transactional} over an audit sink declared {@code MANDATORY}, so the
 * two commit together or not at all. The refusal when an administrator already
 * exists, and the last-administrator guard, both stay where they were.
 *
 * <p><strong>Nothing is derived.</strong> The issuer, the subject and the
 * institutional identifier are all supplied by the operator and checked
 * against the environment. The utility never reads a token, never queries
 * Cognito, and never infers an identity from an email address, a username or
 * a SAML NameID - which attribute the institutional identifier should be is
 * decision D-ID-3 and is still open.
 *
 * <p><strong>Nothing sensitive is logged.</strong> This class has no logger.
 * It returns a {@link BootstrapOutcome} whose lines carry masked identifiers
 * and no token, password or secret; where it goes is the caller's decision.
 */
public final class ArchiveAdminBootstrapCommand {

    /** A Cognito subject is a UUID. Anything else is the wrong field. */
    private static final Pattern COGNITO_SUBJECT =
            Pattern.compile("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-"
                    + "[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");
    private static final Pattern EMAIL_SHAPED = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");

    private final BootstrapTarget target;
    private final ArchiveAdminService admin;
    private final AdminRoleRepository adminRoles;
    private final AdminIdentityRepository identities;
    private final AuthorizationProperties properties;

    public ArchiveAdminBootstrapCommand(
            BootstrapTarget target,
            ArchiveAdminService admin,
            AdminRoleRepository adminRoles,
            AdminIdentityRepository identities,
            AuthorizationProperties properties) {
        this.target = Objects.requireNonNull(target, "target");
        this.admin = Objects.requireNonNull(admin, "admin");
        this.adminRoles = Objects.requireNonNull(adminRoles, "adminRoles");
        this.identities = Objects.requireNonNull(identities, "identities");
        this.properties = properties;
    }

    public BootstrapOutcome run(BootstrapRequest request) {
        Objects.requireNonNull(request, "request");
        List<String> checks = new ArrayList<>();
        List<String> problems = new ArrayList<>();

        checkTarget(request, checks, problems);
        checkIdentity(request, checks, problems);
        checkOperator(request, checks, problems);
        checkNoAdministratorYet(checks, problems);

        if (!problems.isEmpty()) {
            return new BootstrapOutcome(BootstrapOutcome.Result.REFUSED, checks, problems,
                    "Refused: " + problems.size() + " check(s) failed. Nothing was written.",
                    null);
        }

        if (request.dryRun() || !request.confirmed()) {
            // Requirement: a dry run validates and writes NOTHING - not the
            // membership, and not an audit row either. An unconfirmed run is
            // treated as a dry run rather than as an error, so the safe
            // outcome is the one you get by forgetting a flag.
            return new BootstrapOutcome(BootstrapOutcome.Result.DRY_RUN_OK, checks, problems,
                    request.dryRun()
                            ? "Dry run: every check passed. Nothing was written."
                            : "Not confirmed: every check passed, but --confirm was not given. "
                                    + "Nothing was written.",
                    null);
        }

        /*
         * Minted HERE, past every check and past the dry-run return, so a dry
         * run cannot create or reserve one. The value is reported back so the
         * operator records the identifier that was actually written, never a
         * sample from an earlier run.
         */
        InstitutionalIdentifier identity = request.mintArchiveIdentifier()
                ? ArchivePrincipalId.mint()
                : new InstitutionalIdentifier(request.institutionalIdentifier().trim());

        AdminRoleMember member = admin.bootstrapFirstAdministrator(
                identity,
                request.operator().trim(),
                request.reason());

        /*
         * The link goes through ArchiveAdminService, not straight to the
         * repository: the repository write is MANDATORY and the service is
         * what opens the transaction and writes the paired audit row. The
         * actor is the administrator just created - by this point they hold
         * an active membership, so requireAdministrator passes - and the
         * operator who actually ran the utility is named in the reason, so
         * the audit trail records the human rather than only the account.
         *
         * A separate audited act, in its own transaction. If it fails the
         * administrator still exists and can be linked through the API; the
         * reverse - a link to an identity holding no role - would not be
         * recoverable the same way.
         */
        admin.linkIdentity(
                identity,
                request.cognitoIssuer().trim(),
                request.cognitoSubject().trim(),
                identity,
                (KualiPersonId) null,
                request.loginName(),
                "bootstrap by operator=" + request.operator().trim());

        checks.add("WROTE  membership " + member.adminMemberId()
                + " and an ADMIN_VERIFIED identity link");
        return new BootstrapOutcome(BootstrapOutcome.Result.APPLIED, checks, problems,
                "Bootstrapped with archive identity " + identity.value()
                        + " - RECORD THIS VALUE; it is the key every future grant uses. "
                        + "No archive data access was granted: holding ARCHIVE_ADMIN confers "
                        + "none, so this administrator will correctly see no records until a "
                        + "grant is issued through the API.",
                member.adminMemberId());
    }

    private void checkTarget(BootstrapRequest r, List<String> checks, List<String> problems) {
        compare("AWS account", r.awsAccountId(), target.awsAccountId(), checks, problems);
        compare("environment", lower(r.environment()), target.environment(), checks, problems);
        compare("Cognito user pool", r.cognitoUserPoolId(), target.cognitoUserPoolId(),
                checks, problems);

        /*
         * The issuer is derived from the pool rather than configured
         * separately, so a typo in either shows up here instead of producing
         * a membership whose link can never resolve.
         */
        String expected = target.expectedIssuer();
        if (!expected.equals(trim(r.cognitoIssuer()))) {
            problems.add("Cognito issuer does not match this user pool. Expected " + expected);
        } else {
            checks.add("OK     Cognito issuer matches the user pool");
        }

        // Production is never bootstrapped by this utility.
        if ("prod".equals(target.environment()) || "production".equals(target.environment())) {
            problems.add("This utility refuses to run against production. "
                    + "A production administrator needs its own approved procedure.");
        }
    }

    private void checkIdentity(BootstrapRequest r, List<String> checks, List<String> problems) {
        String subject = trim(r.cognitoSubject());
        if (subject == null) {
            problems.add("Cognito subject is required and must be supplied explicitly");
        } else if (!COGNITO_SUBJECT.matcher(subject).matches()) {
            problems.add("Cognito subject must be the immutable 'sub' UUID. "
                    + "A username, an email address or a NameID is not the subject.");
        } else {
            checks.add("OK     Cognito subject is a well-formed immutable sub " + mask(subject));
        }

        if (r.mintArchiveIdentifier()) {
            if (trim(r.institutionalIdentifier()) != null) {
                problems.add("Supply an institutional identifier OR ask for one to be minted, "
                        + "not both - otherwise it is unclear which one would be used.");
            } else {
                checks.add("OK     an archive identifier will be MINTED on confirmation. "
                        + "Nothing is reserved now, so a dry run leaves no identifier behind.");
            }
            return;
        }

        String identifier = trim(r.institutionalIdentifier());
        if (identifier == null) {
            problems.add("Institutional identifier is required. It must be the approved value, "
                    + "never derived from an email address, a username or a SAML NameID "
                    + "(decision D-ID-3 is still open).");
            return;
        }
        checks.add("OK     institutional identifier supplied explicitly " + mask(identifier));

        /*
         * It cannot be checked for correctness - D-ID-3 is open, so there is
         * no authoritative value to compare against. What CAN be checked is
         * whether it looks like one of the things it must not be derived
         * from, and that is worth an explicit acknowledgement rather than a
         * silent accept.
         */
        boolean looksDerived = EMAIL_SHAPED.matcher(identifier).matches()
                || identifier.equalsIgnoreCase(trim(r.loginName()));
        if (looksDerived && !r.acceptProvisionalIdentifier()) {
            problems.add("The institutional identifier looks like an email address or the "
                    + "Cognito username. That may be the approved provisional value, but it "
                    + "carries the risk that a reassigned address takes the membership with "
                    + "it. Re-run with the explicit provisional acknowledgement to accept it.");
        } else if (looksDerived) {
            checks.add("WARN   identifier is email-shaped; accepted as PROVISIONAL. "
                    + "It must be re-keyed once BU IAM settles D-ID-3.");
        }

        if (identifier.equalsIgnoreCase(subject)) {
            problems.add("The institutional identifier must not be the Cognito subject: "
                    + "that is a Cognito artefact, not a BU institutional identity.");
        }
    }

    private void checkOperator(BootstrapRequest r, List<String> checks, List<String> problems) {
        if (trim(r.operator()) == null) {
            problems.add("The operator identity is required, so the chain of custody starts "
                    + "at a recorded person rather than an unexplained row.");
        } else {
            checks.add("OK     operator recorded for the audit row");
        }
        if (trim(r.reason()) == null) {
            problems.add("A reason is required; an unexplained audit row is a poor audit row.");
        }
    }

    private void checkNoAdministratorYet(List<String> checks, List<String> problems) {
        /*
         * Advisory only. The authoritative refusal is inside
         * AdminRoleRepository.bootstrap, under a lock, in the same
         * transaction as the insert. Checking here as well turns the common
         * case into a clear message instead of a stack trace.
         */
        int active = adminRoles.countActive();
        if (active > 0) {
            problems.add("An active ARCHIVE_ADMIN already exists (" + active + "). "
                    + "Appoint further administrators through an existing one.");
        } else {
            checks.add("OK     no active administrator exists yet");
        }

        if (properties != null && properties.isEnforcementEnabled()) {
            checks.add("NOTE   enforcement is ON in this environment");
        } else {
            checks.add("NOTE   enforcement is OFF; the administrator will exist but the "
                    + "administrative API stays 404 until it is enabled");
        }
    }

    private static void compare(
            String what, String supplied, String expected,
            List<String> checks, List<String> problems) {
        if (!expected.equals(supplied)) {
            problems.add(what + " does not match this environment. Expected " + expected
                    + ", operator supplied " + (supplied == null ? "nothing" : supplied));
        } else {
            checks.add("OK     " + what + " matches (" + expected + ")");
        }
    }

    /** Enough to recognise a value you already know, not enough to learn one. */
    static String mask(String value) {
        if (value == null || value.isBlank()) {
            return "[none]";
        }
        String trimmed = value.trim();
        return trimmed.length() <= 4
                ? "[****]"
                : "[" + trimmed.substring(0, 2) + "****" + trimmed.substring(trimmed.length() - 2) + "]";
    }

    private static String trim(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static String lower(String value) {
        String trimmed = trim(value);
        return trimmed == null ? null : trimmed.toLowerCase(Locale.ROOT);
    }
}
