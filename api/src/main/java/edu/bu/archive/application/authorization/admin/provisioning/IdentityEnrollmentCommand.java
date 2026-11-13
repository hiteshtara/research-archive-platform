package edu.bu.archive.application.authorization.admin.provisioning;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;

import edu.bu.archive.application.authorization.ArchivePrincipalId;
import edu.bu.archive.application.authorization.IdentityLink;
import edu.bu.archive.application.authorization.IdentityLinkRepository;
import edu.bu.archive.application.authorization.InstitutionalIdentifier;
import edu.bu.archive.application.authorization.ValidatedCognitoIdentity;
import edu.bu.archive.application.authorization.KualiPersonId;
import edu.bu.archive.application.authorization.admin.AdminIdentityRepository;
import edu.bu.archive.application.authorization.admin.AdminRoleRepository;
import edu.bu.archive.application.authorization.admin.ArchiveAdminService;

/**
 * Enrols an ordinary BU research user: one verified identity link, no
 * administrator membership and no research-data grant.
 *
 * <p>It exists because there was no path to do this at all. The bootstrap
 * utility creates the FIRST ARCHIVE_ADMIN and refuses once one exists; the
 * administrative API's link endpoint 404s whenever enforcement is off, which
 * is always today; and automatic enrollment is gated on enforcement AND
 * enrollment both being on, with no {@code CognitoProfileReader} shipped. So
 * the only identity in DEV was the administrator's own, and a lifecycle test
 * that must not use the administrator as its grantee had nothing to use.
 *
 * <p><strong>Not a second identity authority.</strong> The write is
 * {@link ArchiveAdminService#linkIdentity}, the same method the administrative
 * API calls. That method requires an ACTIVE ARCHIVE_ADMIN actor, refuses an
 * identity with any prior link history - which is what forces a deliberate
 * re-link instead - and writes the {@code IDENTITY_LINKED} audit row inside
 * its own transaction. This class validates and sequences; it does not
 * implement the rule.
 *
 * <p><strong>Nothing is derived from an email address.</strong> The identity is
 * selected by the immutable Cognito {@code sub}, corroborated against the user
 * pool. {@code --login-name} is recorded for display and is never used to
 * choose anyone. The archive identifier is minted past every check and past
 * the dry-run return, so a dry run cannot reserve one.
 */
public final class IdentityEnrollmentCommand {

    /** A Cognito subject is a UUID. Anything else is the wrong field. */
    private static final Pattern COGNITO_SUBJECT =
            Pattern.compile("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-"
                    + "[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");
    private static final Pattern ISSUER = Pattern.compile(
            "^https://cognito-idp\\.[a-z0-9-]+\\.amazonaws\\.com/[a-z0-9-]+_[A-Za-z0-9]+$");
    private static final Pattern ARP =
            Pattern.compile("^ARP-[0-9A-HJKMNP-TV-Z]{8}-[0-9A-HJKMNP-TV-Z]{8}$");
    private static final Pattern EMAIL_SHAPED =
            Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");

    private final ArchiveAdminService admin;
    private final AdminRoleRepository adminRoles;
    private final AdminIdentityRepository identities;
    private final IdentityLinkRepository links;
    private final RuntimeIdentity runtime;
    private CognitoDirectory directory = CognitoDirectory.UNAVAILABLE;

    public IdentityEnrollmentCommand(ArchiveAdminService admin, AdminRoleRepository adminRoles,
                                     AdminIdentityRepository identities, IdentityLinkRepository links,
                                     RuntimeIdentity runtime) {
        this.admin = Objects.requireNonNull(admin);
        this.adminRoles = Objects.requireNonNull(adminRoles);
        this.identities = Objects.requireNonNull(identities);
        this.links = Objects.requireNonNull(links);
        this.runtime = Objects.requireNonNull(runtime);
    }

    /** The pool reader. Absent means a confirmed enrollment is refused. */
    public IdentityEnrollmentCommand withDirectory(CognitoDirectory directory) {
        this.directory = Objects.requireNonNull(directory);
        return this;
    }

    public EnrollmentOutcome run(EnrollmentRequest request) {
        List<String> checks = new ArrayList<>();
        List<String> problems = new ArrayList<>();
        boolean willWrite = request.confirmed() && !request.dryRun();

        RuntimeVerification.verify(runtime, trim(request.awsAccountId()),
                lower(request.environment()), willWrite, checks, problems);
        checkProvenance(request, checks, problems);

        InstitutionalIdentifier actor = checkActor(request, checks, problems);
        String subject = checkCognitoIdentity(request, checks, problems);
        checkPool(request, subject, willWrite, checks, problems);

        if (!problems.isEmpty()) {
            return new EnrollmentOutcome(EnrollmentOutcome.Result.REFUSED, checks, problems,
                    "Refusing to enrol: " + problems.size() + " problem(s). Nothing was written.",
                    null, null);
        }
        if (!willWrite) {
            return new EnrollmentOutcome(EnrollmentOutcome.Result.DRY_RUN_OK, checks, problems,
                    request.dryRun()
                            ? "Dry run: every check passed. Nothing was written, and no archive "
                                    + "identifier was minted."
                            : "Not confirmed: every check passed, but --confirm was not given. "
                                    + "Nothing was written.",
                    null, null);
        }

        /*
         * Minted HERE, past every check and past the dry-run return, so a dry
         * run can neither create nor reserve an identifier. The value is
         * reported back because it is the key every future grant uses.
         */
        InstitutionalIdentifier minted = ArchivePrincipalId.mint();

        /*
         * ONE call. linkIdentity checks the acting administrator, refuses an
         * identity that already has link history, creates the ACTIVE link and
         * writes IDENTITY_LINKED - all in one transaction. A failure there
         * rolls back the link and the audit row together, so there is no
         * orphan identity and no missing audit event.
         *
         * No KualiPersonId is supplied: a research user's KIM principal is not
         * something this utility can verify, and guessing one would hand out
         * contact-derived access under a VERIFIED_PRINCIPAL policy.
         */
        IdentityLink link = admin.linkIdentity(
                actor, trim(request.cognitoIssuer()), subject, minted,
                (KualiPersonId) null, trim(request.loginName()),
                "operator=" + request.operator().trim()
                        + "; approval=" + request.approvalReference().trim()
                        + "; " + request.reason().trim());

        checks.add("WROTE  identity link " + link.id() + " and an IDENTITY_LINKED audit row");
        return new EnrollmentOutcome(EnrollmentOutcome.Result.APPLIED, checks, problems,
                "Enrolled archive identity " + minted.value()
                        + " - RECORD THIS VALUE; it is the key every future grant uses. "
                        + "No administrator membership and no research-data grant were created, "
                        + "so this identity currently sees nothing.",
                link.id(), minted.value());
    }

    private void checkProvenance(EnrollmentRequest r, List<String> checks, List<String> problems) {
        int before = problems.size();
        if (trim(r.operator()) == null) {
            problems.add("--operator is required: the audit row records the person, not the account");
        }
        if (trim(r.reason()) == null) {
            problems.add("--reason is required");
        }
        if (trim(r.approvalReference()) == null) {
            problems.add("--approval-reference is required, so the audit row points at the approval");
        }
        if (problems.size() == before) {
            checks.add("OK     operator, reason and approval reference recorded for the audit row");
        }
    }

    private InstitutionalIdentifier checkActor(
            EnrollmentRequest r, List<String> checks, List<String> problems) {
        String raw = trim(r.actorIdentifier());
        if (raw == null) {
            problems.add("--actor is required: the acting ARCHIVE_ADMIN's archive identifier");
            return null;
        }
        if (!ARP.matcher(raw).matches()) {
            problems.add("--actor must be an archive identifier of the form ARP-XXXXXXXX-XXXXXXXX");
            return null;
        }
        InstitutionalIdentifier actor = new InstitutionalIdentifier(raw);
        if (adminRoles.findActive(actor).isEmpty()) {
            problems.add("the acting administrator " + mask(raw) + " holds no ACTIVE "
                    + "ARCHIVE_ADMIN membership. Enrollment must be performed by a real "
                    + "administrator.");
            return actor;
        }
        checks.add("OK     acting administrator holds an ACTIVE membership");
        return actor;
    }

    private String checkCognitoIdentity(
            EnrollmentRequest r, List<String> checks, List<String> problems) {
        String issuer = trim(r.cognitoIssuer());
        String subject = trim(r.cognitoSubject());

        if (issuer == null || !ISSUER.matcher(issuer).matches()) {
            problems.add("--cognito-issuer must be the user pool's issuer URL, "
                    + "https://cognito-idp.<region>.amazonaws.com/<pool-id>");
        } else {
            checks.add("OK     Cognito issuer is a well-formed user-pool issuer");
        }

        if (subject == null) {
            problems.add("--cognito-subject is required and must be supplied explicitly");
            return null;
        }
        if (EMAIL_SHAPED.matcher(subject).matches()) {
            problems.add("--cognito-subject is an email address. An identity is NEVER selected "
                    + "by email: a reassigned address would take the identity with it. Supply "
                    + "the immutable sub UUID.");
            return null;
        }
        if (!COGNITO_SUBJECT.matcher(subject).matches()) {
            problems.add("--cognito-subject must be the immutable 'sub' UUID. A username, an "
                    + "email address or a NameID is not the subject.");
            return null;
        }
        checks.add("OK     Cognito subject is a well-formed immutable sub " + mask(subject));
        return subject;
    }

    /**
     * Corroborate the typed subject against the live pool, and refuse an
     * identity that is already linked or has link history.
     */
    private void checkPool(EnrollmentRequest r, String subject, boolean willWrite,
                           List<String> checks, List<String> problems) {
        String pool = trim(r.cognitoUserPoolId());
        if (pool == null) {
            problems.add("--cognito-user-pool-id is required: the subject is verified against "
                    + "the pool, not taken on trust");
            return;
        }
        if (trim(r.cognitoIssuer()) != null && !trim(r.cognitoIssuer()).endsWith("/" + pool)) {
            problems.add("--cognito-issuer does not name pool " + pool
                    + ". The issuer and the pool must describe the same user pool.");
            return;
        }
        if (subject == null) {
            return;      // already reported
        }

        Optional<CognitoDirectory.PoolUser> found;
        try {
            found = directory.findBySubject(pool, subject);
        } catch (RuntimeException e) {
            // Unverifiable is not absent. Fail closed for a write.
            String detail = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            if (willWrite) {
                problems.add("the Cognito user pool could not be read, so the subject cannot be "
                        + "verified: " + detail + ". Refusing to write.");
            } else {
                checks.add("WARN   the Cognito user pool could not be read, so a dry run cannot "
                        + "corroborate the subject: " + detail);
            }
            return;
        }

        if (found.isEmpty()) {
            problems.add("pool " + pool + " holds no user with subject " + mask(subject)
                    + ". Refusing: an identity that cannot be found cannot be verified.");
            return;
        }
        CognitoDirectory.PoolUser user = found.get();
        if (!user.enabled()) {
            problems.add("the Cognito user for subject " + mask(subject)
                    + " is disabled. Refusing to enrol a disabled identity.");
            return;
        }
        /*
         * Refuse an already-linked subject HERE, with a sentence. The
         * repository would throw IllegalStateException anyway - V085 and
         * ux_identity_link_active_cognito guarantee it - but an operator
         * deserves the reason rather than a stack trace, and a clean refusal
         * cannot be mistaken for a transient failure.
         */
        List<IdentityLink> existing = links.findByCognitoIdentity(
                new ValidatedCognitoIdentity(trim(r.cognitoIssuer()), subject));
        List<IdentityLink> active = existing.stream().filter(IdentityLink::isActive).toList();
        if (!active.isEmpty()) {
            problems.add("Cognito subject " + mask(subject) + " is already linked to archive "
                    + "identity " + mask(active.get(0).institutionalIdentifier().value())
                    + " (link " + active.get(0).id() + "). Enrolling it again would make "
                    + "resolution ambiguous. If this person's pool was replaced, that is a "
                    + "RE-LINK - a separate, separately audited act with its own approval.");
            return;
        }
        if (!existing.isEmpty()) {
            problems.add("Cognito subject " + mask(subject) + " has " + existing.size()
                    + " revoked link(s) in its history. Re-enrolling it under a NEW minted "
                    + "identity would silently detach it from whatever those links granted. "
                    + "Refusing: resolve the history deliberately.");
            return;
        }
        checks.add("OK     Cognito subject is not linked to any archive identity, live or revoked");

        checks.add("OK     subject " + mask(subject) + " exists in pool " + pool
                + ", enabled, status " + user.status()
                + (user.isFederated() ? " (BU Shibboleth federated)" : " (native pool user)"));
        if (!user.isFederated()) {
            checks.add("NOTE   this is a NATIVE pool user, not a BU Shibboleth one. A research "
                    + "user normally signs in through BU, so check this is deliberate.");
        }
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

    /** Identifiers are not secrets, but they are not log fodder either. */
    private static String mask(String value) {
        if (value == null || value.length() <= 4) {
            return "****";
        }
        return value.substring(0, 2) + "****" + value.substring(value.length() - 2);
    }
}
