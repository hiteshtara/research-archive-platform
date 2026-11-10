package edu.bu.archive.application.authorization.admin.provisioning;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import edu.bu.archive.application.authorization.IdentityLink;
import edu.bu.archive.application.authorization.InstitutionalIdentifier;
import edu.bu.archive.application.authorization.KualiPersonId;
import edu.bu.archive.application.authorization.admin.AdminIdentityRepository;
import edu.bu.archive.application.authorization.admin.AdminRoleRepository;
import edu.bu.archive.application.authorization.admin.ArchiveAdminService;

/**
 * Link a Cognito identity to an archive identity, as an operator, for people
 * who are not administrators.
 *
 * <h2>Why this utility has to exist</h2>
 *
 * <p>An identity link is the prerequisite for every other authorization test:
 * grants hang off an archive identity, and nothing resolves a signed-in user
 * to one without a link. There were two ways to create a link and neither
 * could produce a test population.
 *
 * <ul>
 *   <li>{@code POST /api/v1/admin/identities/{id}/links} - the administrative
 *       API, whose whole tree 404s while {@code enforcement-enabled} is false,
 *       which it is in every environment.
 *   <li>The ARCHIVE_ADMIN bootstrap utility, which creates a link only as part
 *       of making that person an administrator.
 * </ul>
 *
 * <p>So the only available route granted ARCHIVE_ADMIN to everyone it touched,
 * and the correct route was unreachable precisely while enforcement was off.
 * Turning enforcement on first to reach it would mean enabling it with one
 * link and no grants - denying every record to every user, which is what the
 * startup safeguard refuses to let happen quietly.
 *
 * <p>{@code IdentityEnrollmentService} exists and is wired as a bean, but no
 * controller or runner calls it: automatic enrollment is unshipped. It is not
 * a route either.
 *
 * <p>This utility is therefore the same shape as grant provisioning and
 * administrator bootstrap, and for the same reason those are utilities: an
 * operator-run, audited, dry-run-by-default act performed before enforcement
 * is switched on. It calls {@link ArchiveAdminService#linkIdentity}, the same
 * method the administrative API calls, so there is one link implementation
 * and one audit row.
 *
 * <h2>What a link is not</h2>
 *
 * <p><strong>A link confers no access to any record.</strong> It says which
 * archive identity a signed-in person is. Access comes from grants, issued
 * separately. The utility prints this on every run, because the pilot includes
 * a Grant Administrator who must be able to administer access without holding
 * any, and an operator who conflates the two would hand them the archive.
 */
public final class IdentityLinkCommand {

    private final String expectedEnvironment;
    private final ArchiveAdminService admin;
    private final AdminRoleRepository adminRoles;
    private final AdminIdentityRepository identities;
    private final RuntimeIdentity runtime;

    public IdentityLinkCommand(
            String expectedEnvironment,
            ArchiveAdminService admin,
            AdminRoleRepository adminRoles,
            AdminIdentityRepository identities,
            RuntimeIdentity runtime) {

        this.expectedEnvironment = expectedEnvironment.toLowerCase(Locale.ROOT);
        this.admin = admin;
        this.adminRoles = adminRoles;
        this.identities = identities;
        this.runtime = runtime;
    }

    public IdentityLinkOutcome run(IdentityLinkRequest request) {
        List<String> checks = new ArrayList<>();
        List<String> problems = new ArrayList<>();

        OperatorChecks.environment(
                runtime,
                OperatorChecks.trim(request.awsAccountId()),
                OperatorChecks.lower(request.environment()),
                expectedEnvironment,
                request.confirmed() && !request.dryRun(),
                checks,
                problems);
        OperatorChecks.provenance(
                request.operator(), request.reason(), request.approvalReference(),
                checks, problems);

        InstitutionalIdentifier actor = OperatorChecks.identifier(
                request.actorIdentifier(), "acting administrator", checks, problems);
        InstitutionalIdentifier subject = OperatorChecks.identifier(
                request.subjectIdentifier(), "identity being linked", checks, problems);

        String cognitoSubject = OperatorChecks.cognitoSubject(
                request.cognitoSubject(), checks, problems);
        String issuer = checkIssuer(request, checks, problems);

        Optional<KualiPersonId> principal = checkPrincipal(request, checks, problems);

        if (actor != null) {
            if (adminRoles.findActive(actor).isEmpty()) {
                problems.add("the acting administrator " + OperatorChecks.mask(actor.value())
                        + " holds no ACTIVE ARCHIVE_ADMIN membership. "
                        + "Identity links must be created by a real administrator.");
            } else {
                checks.add("OK     acting administrator holds an ACTIVE membership");
            }
        }

        if (subject != null) {
            checkNotAlreadyLinked(subject, checks, problems);
        }
        if (subject != null && cognitoSubject != null && subject.value().equalsIgnoreCase(cognitoSubject)) {
            problems.add("the archive identifier must not be the Cognito subject: "
                    + "that is a Cognito artefact, not a BU institutional identity.");
        }

        /*
         * Stated, never refused. An administrator may legitimately be linked
         * by this utility - they are a person who signs in like anyone else -
         * but a run that links the actor to themselves is worth reading twice,
         * because it is also what a mistyped --subject-identifier looks like.
         */
        if (actor != null && actor.equals(subject)) {
            checks.add("NOTE   the acting administrator is linking THEMSELVES. That is "
                    + "legitimate, but check it was intended.");
        }

        checks.add("NOTE   a link confers NO access to any record. It establishes who the "
                + "signed-in person is. Access requires a grant, issued separately.");

        if (!problems.isEmpty()) {
            return new IdentityLinkOutcome(IdentityLinkOutcome.Result.REFUSED, checks, problems,
                    "Refusing to link: " + problems.size() + " problem(s). Nothing was written.",
                    null);
        }
        if (request.dryRun() || !request.confirmed()) {
            return new IdentityLinkOutcome(IdentityLinkOutcome.Result.DRY_RUN_OK, checks, problems,
                    request.dryRun()
                            ? "Dry run: every check passed. Nothing was written."
                            : "Not confirmed: every check passed, but --confirm was not given. "
                                    + "Nothing was written.",
                    null);
        }

        IdentityLink created;
        try {
            created = admin.linkIdentity(
                    actor, issuer, cognitoSubject, subject, principal.orElse(null),
                    OperatorChecks.trim(request.loginName()),
                    "operator=" + request.operator().trim()
                            + "; approval=" + request.approvalReference().trim()
                            + "; " + request.reason().trim());
        } catch (IllegalStateException e) {
            /*
             * One of the two partial unique indexes on authz.identity_link
             * fired. The pre-check above covers the archive-identity side
             * (ux_identity_link_active_identity); this is reached for the
             * Cognito side (ux_identity_link_active_cognito), where the same
             * Cognito subject is already linked to a DIFFERENT archive
             * identity - a case this utility cannot see in advance, because
             * nothing reads links by Cognito subject on the administrative
             * side.
             *
             * It is also the concurrent case for either index: the pre-check
             * is a read followed by a write with no lock.
             *
             * Reported as a refusal rather than rethrown, so the operator
             * gets the sentence instead of a stack trace. Nothing partial
             * survives - linkIdentity is one transaction over the link and
             * its IDENTITY_LINKED audit row.
             */
            problems.add("the database refused this link: " + e.getMessage()
                    + " This usually means the Cognito subject is already linked to a "
                    + "different archive identity. Nothing was written. Resolve which "
                    + "identity is correct rather than creating a second link.");
            return new IdentityLinkOutcome(IdentityLinkOutcome.Result.REFUSED, checks, problems,
                    "Refusing to link: the database rejected it. Nothing was written.", null);
        }

        return new IdentityLinkOutcome(IdentityLinkOutcome.Result.APPLIED, checks, problems,
                "Linked. The person can now be resolved to " + OperatorChecks.mask(subject.value())
                        + " when they sign in. They still hold no access until a grant is issued.",
                created.id());
    }

    /**
     * The issuer, derived from the pool rather than trusted as its own value.
     *
     * <p>Both are flags, so accepting them independently would let a typo in
     * either produce a link that no real token can match - and that failure
     * surfaces much later, as a person who signs in successfully and has no
     * access.
     */
    private String checkIssuer(
            IdentityLinkRequest r, List<String> checks, List<String> problems) {

        String pool = OperatorChecks.trim(r.cognitoUserPoolId());
        String region = OperatorChecks.trim(r.cognitoRegion());
        if (pool == null) {
            problems.add("--cognito-user-pool-id is required");
        }
        if (region == null) {
            problems.add("--cognito-region is required, so the expected issuer can be derived");
        }
        if (pool == null || region == null) {
            return null;
        }
        String expected = "https://cognito-idp." + region + ".amazonaws.com/" + pool;
        OperatorChecks.cognitoIssuer(r.cognitoIssuer(), expected, checks, problems);
        return expected;
    }

    /**
     * The Kuali PERSON_ID, and what it means to omit it.
     *
     * <p>Omitting it is correct for CENTRAL, UNIT, IO and PARENT_AWARD access.
     * It is silently fatal for CONTACT_DERIVATION: {@code AccessScopeResolver}
     * only carries a contact person when the mapping HAS a PERSON_ID, so the
     * contact clause is never added to the scope and never added to the list
     * SQL. The grant exists, the table shows it, and the person sees nothing.
     *
     * <p>So the omission is reported as a check line naming the consequence,
     * not left as a blank field. Refusing would be wrong - most links do not
     * need one, and BU IAM has not settled how PERSON_IDs are verified
     * (D-ID-3) - but an operator setting up a contact-derivation test needs to
     * be told before the test, not after it appears to fail.
     */
    private Optional<KualiPersonId> checkPrincipal(
            IdentityLinkRequest r, List<String> checks, List<String> problems) {

        String raw = OperatorChecks.trim(r.kualiPersonId());
        if (raw == null) {
            checks.add("NOTE   no Kuali PERSON_ID supplied. CENTRAL, UNIT, IO and PARENT_AWARD "
                    + "access work without one. CONTACT_DERIVATION does NOT: a mapping with no "
                    + "PERSON_ID never matches a contact row, so such a grant would be issued "
                    + "and confer nothing. Supply --kuali-person-id for a contact-derivation "
                    + "test identity.");
            return Optional.empty();
        }
        if (!raw.matches("^[0-9]{1,40}$")) {
            problems.add("--kuali-person-id must be the numeric Kuali PERSON_ID. "
                    + "A username, an email address or an archive identifier is not a PERSON_ID.");
            return Optional.empty();
        }
        checks.add("OK     Kuali PERSON_ID supplied; contact-derived access can resolve");
        return Optional.of(new KualiPersonId(raw));
    }

    /**
     * That the archive identity has no ACTIVE link already.
     *
     * <p>V085 allows one active link per archive identity. A second one is not
     * an additional sign-in route, it is a contradiction about who the person
     * is, and re-linking is a separate deliberate act because it hands the
     * identity's existing grants to a different Cognito subject.
     */
    private void checkNotAlreadyLinked(
            InstitutionalIdentifier subject, List<String> checks, List<String> problems) {

        List<IdentityLink> existing = identities.findLinksFor(subject);
        Optional<IdentityLink> active = existing.stream().filter(IdentityLink::isActive).findFirst();

        if (active.isPresent()) {
            problems.add("archive identity " + OperatorChecks.mask(subject.value())
                    + " already has an ACTIVE link (id " + active.get().id() + "). "
                    + "One active link per identity is deliberate. If the person's Cognito "
                    + "identity has genuinely changed, that is a re-link, which transfers "
                    + "their grants and is a separate approved act - not a second link.");
        } else if (!existing.isEmpty()) {
            problems.add("archive identity " + OperatorChecks.mask(subject.value())
                    + " has " + existing.size() + " prior (revoked) link(s) but no active one. "
                    + "Re-establishing a used identity is a re-link, not a link, so that the "
                    + "audit trail records the grants that moved with it.");
        } else {
            checks.add("OK     archive identity has never been linked");
        }
    }
}
