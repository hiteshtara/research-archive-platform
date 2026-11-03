package edu.bu.archive.application.authorization.admin.provisioning;

import java.time.Clock;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;

import edu.bu.archive.application.authorization.AccessGrant;
import edu.bu.archive.application.authorization.AccessGrantRepository;
import edu.bu.archive.application.authorization.GrantType;
import edu.bu.archive.application.authorization.InstitutionalIdentifier;
import edu.bu.archive.application.authorization.admin.AdminRoleRepository;
import edu.bu.archive.application.authorization.admin.DuplicateActiveGrantException;
import edu.bu.archive.application.authorization.admin.ArchiveAdminService;
import edu.bu.archive.application.authorization.admin.GrantSpecification;

/**
 * Issues the first research-data grants, before the administrative API is
 * reachable.
 *
 * <p>It exists because of a circularity. Grants must exist BEFORE
 * record-level enforcement is switched on — with none, enforcement makes
 * every record invisible to everyone, the administrator included. But the
 * administrative API 404s while enforcement is off, so the API cannot
 * create the grants that would make enabling enforcement safe. This breaks
 * that circle without adding an HTTP surface.
 *
 * <p>It introduces no new write path and no new audit path: every change
 * goes through {@link ArchiveAdminService}, which already opens the
 * transaction, checks the acting administrator and writes the paired audit
 * row. A bug here cannot produce an unaudited grant.
 */
public final class GrantProvisioningCommand {

    /** Crockford base32, as minted by ArchivePrincipalId. */
    private static final Pattern ARP = Pattern.compile("^ARP-[0-9A-HJKMNP-TV-Z]{8}-[0-9A-HJKMNP-TV-Z]{8}$");
    private static final Pattern UNIT = Pattern.compile("^[0-9]{4,12}$");

    private final String expectedAccountId;
    private final String expectedEnvironment;
    private final RuntimeIdentity runtime;
    private final ArchiveAdminService admin;
    private final AdminRoleRepository adminRoles;
    private final AccessGrantRepository grants;
    /** Test seam: a fixed Clock makes expiry assertions deterministic. */
    private Clock clock = Clock.systemUTC();

    public GrantProvisioningCommand(
            String expectedAccountId, String expectedEnvironment,
            ArchiveAdminService admin, AdminRoleRepository adminRoles,
            AccessGrantRepository grants, RuntimeIdentity runtime) {
        this.expectedAccountId = Objects.requireNonNull(expectedAccountId);
        this.expectedEnvironment = expectedEnvironment.toLowerCase(Locale.ROOT);
        this.runtime = Objects.requireNonNull(runtime);
        this.admin = Objects.requireNonNull(admin);
        this.adminRoles = Objects.requireNonNull(adminRoles);
        this.grants = Objects.requireNonNull(grants);
    }

    /** Override the clock used to judge whether an expiry is in the future. */
    public GrantProvisioningCommand withClock(Clock clock) {
        this.clock = Objects.requireNonNull(clock, "clock");
        return this;
    }

    public GrantOutcome run(GrantRequest request) {
        return request.isRevocation() ? revoke(request) : create(request);
    }

    /**
     * Withdraw a grant by its immutable id.
     *
     * <p>Delegates the write to {@link ArchiveAdminService#revokeAccess}, the
     * same method the administrative API uses, so there is ONE revocation
     * implementation: it checks the acting administrator, flips revoked_at and
     * writes the ACCESS_REVOKED audit row in a single transaction. Nothing is
     * deleted - revocation is a status change, and the grant row and both
     * audit rows survive.
     */
    private GrantOutcome revoke(GrantRequest request) {
        List<String> checks = new ArrayList<>();
        List<String> problems = new ArrayList<>();

        checkEnvironment(request, checks, problems);
        rejectCreateOnlyInputs(request, problems);
        InstitutionalIdentifier actor = checkIdentifier(
                request.actorIdentifier(), "acting administrator", checks, problems);
        checkProvenance(request, checks, problems);
        Long grantId = checkGrantId(request, checks, problems);

        AccessGrant target = null;
        if (grantId != null) {
            target = grants.findById(grantId).orElse(null);
            if (target == null) {
                problems.add("no grant has id " + grantId
                        + ". Revocation takes the immutable grant_id, not a grantee.");
            } else if (target.revokedAt() != null) {
                /*
                 * Refused rather than treated as success. ArchiveAdminService
                 * would throw here anyway, before writing the audit row - this
                 * just says so in a sentence instead of a stack trace, and
                 * means a repeated revocation cannot leave a second
                 * ACCESS_REVOKED entry suggesting it happened twice.
                 */
                problems.add("grant " + grantId + " was already revoked at "
                        + target.revokedAt() + ". Nothing to do; a second revocation would "
                        + "imply a withdrawal that did not happen.");
            } else {
                checks.add("OK     grant " + grantId + " is live: " + describe(target));
            }
        }

        if (actor != null && adminRoles.findActive(actor).isEmpty()) {
            problems.add("the acting administrator " + mask(actor.value())
                    + " holds no ACTIVE ARCHIVE_ADMIN membership. "
                    + "Revocations must be issued by a real administrator.");
        } else if (actor != null) {
            checks.add("OK     acting administrator holds an ACTIVE membership");
        }

        if (!problems.isEmpty()) {
            return new GrantOutcome(GrantOutcome.Result.REFUSED, checks, problems,
                    "Refusing to revoke: " + problems.size() + " problem(s). Nothing was written.",
                    null);
        }
        if (request.dryRun() || !request.confirmed()) {
            return new GrantOutcome(GrantOutcome.Result.DRY_RUN_OK, checks, problems,
                    request.dryRun()
                            ? "Dry run: every check passed. Nothing was written."
                            : "Not confirmed: every check passed, but --confirm was not given. "
                                    + "Nothing was written.",
                    grantId);
        }

        admin.revokeAccess(actor, grantId,
                "operator=" + request.operator().trim()
                        + "; approval=" + request.approvalReference().trim()
                        + "; " + request.reason().trim());

        checks.add("WROTE  grant " + grantId + " revoked; ACCESS_REVOKED audit row written");
        return new GrantOutcome(GrantOutcome.Result.APPLIED, checks, problems,
                "Revoked grant " + grantId + ". The row survives with revoked_at set, and the "
                        + "scope is free to be granted again - V087 constrains unrevoked grants "
                        + "only. Enforcement is unchanged.",
                grantId);
    }

    /** One invocation is a creation or a revocation, never both. */
    private static void rejectCreateOnlyInputs(GrantRequest r, List<String> problems) {
        if (trim(r.grantType()) != null || trim(r.granteeIdentifier()) != null
                || trim(r.unitNumber()) != null || trim(r.ioValue()) != null
                || r.includeDescendants() || trim(r.expiresAt()) != null) {
            problems.add("--revoke-grant-id cannot be combined with --type, --grantee, "
                    + "--unit-number, --io-value, --include-descendants or --expires-at. "
                    + "Revoking and granting are separate, separately audited acts.");
        }
    }

    private Long checkGrantId(GrantRequest r, List<String> checks, List<String> problems) {
        String raw = trim(r.revokeGrantId());
        try {
            long id = Long.parseLong(raw);
            if (id <= 0) {
                problems.add("--revoke-grant-id must be a positive grant_id, got " + raw);
                return null;
            }
            checks.add("OK     revoking by immutable grant_id " + id);
            return id;
        } catch (NumberFormatException e) {
            problems.add("--revoke-grant-id must be a number, got '" + raw + "'");
            return null;
        }
    }

    private static String describe(AccessGrant grant) {
        StringBuilder text = new StringBuilder(grant.type().name());
        if (grant.unitNumber() != null) {
            text.append(" unit ").append(grant.unitNumber());
            text.append(grant.includeDescendants() ? " including descendants" : " only");
        }
        if (grant.ioValue() != null) {
            text.append(" IO ").append(mask(grant.ioValue()));
        }
        return text.append(" for ").append(mask(grant.grantee().value())).toString();
    }

    private GrantOutcome create(GrantRequest request) {
        List<String> checks = new ArrayList<>();
        List<String> problems = new ArrayList<>();

        checkEnvironment(request, checks, problems);
        GrantType type = checkType(request, checks, problems);
        InstitutionalIdentifier grantee = checkIdentifier(
                request.granteeIdentifier(), "grantee", checks, problems);
        InstitutionalIdentifier actor = checkIdentifier(
                request.actorIdentifier(), "acting administrator", checks, problems);
        checkScope(request, type, checks, problems);
        Instant expiresAt = checkExpiry(request, checks, problems);
        checkProvenance(request, checks, problems);
        if (grantee != null && actor != null && type != null) {
            checkSubjects(request, grantee, actor, type, checks, problems);
        }

        if (!problems.isEmpty()) {
            return new GrantOutcome(GrantOutcome.Result.REFUSED, checks, problems,
                    "Refusing to provision: " + problems.size() + " problem(s). Nothing was written.",
                    null);
        }
        if (request.dryRun() || !request.confirmed()) {
            return new GrantOutcome(GrantOutcome.Result.DRY_RUN_OK, checks, problems,
                    request.dryRun()
                            ? "Dry run: every check passed. Nothing was written."
                            : "Not confirmed: every check passed, but --confirm was not given. "
                                    + "Nothing was written.",
                    null);
        }

        /*
         * One call. ArchiveAdminService.grantAccess is @Transactional and
         * writes the ACCESS_GRANTED audit row inside that transaction, so
         * the grant and its audit row commit together or not at all.
         */
        AccessGrant created;
        try {
            created = admin.grantAccess(actor, grantee,
                    new GrantSpecification(type, trim(request.unitNumber()),
                            request.includeDescendants(), trim(request.ioValue()),
                            expiresAt,
                            "operator=" + request.operator().trim()
                                    + "; approval=" + request.approvalReference().trim()
                                    + "; " + request.reason().trim()));
        } catch (DuplicateActiveGrantException e) {
            /*
             * V087's ux_access_grant_active_scope fired. The duplicate check
             * above is a read followed by a write with no lock, so two
             * concurrent identical requests can both pass it; the index is
             * what actually holds the invariant, and the loser arrives here.
             *
             * It is reported as the same refusal the sequential case gives,
             * because it is the same situation: the grant already exists.
             * ArchiveAdminService translates the index violation into
             * DuplicateActiveGrantException, so this CLI and the
             * administrative API agree on what a duplicate is instead of each
             * deciding separately. Previously this escaped as a raw
             * DataIntegrityViolationException and the operator saw a stack
             * trace instead of an answer.
             *
             * Nothing partial survives: grantAccess is one transaction over
             * the grant and its ACCESS_GRANTED audit row, so the violation
             * rolled both back.
             */
            problems.add("an identical active grant already exists for this grantee and "
                    + "scope - the database constraint refused a duplicate. Nothing was "
                    + "written. If the existing grant is wrong, revoke it rather than "
                    + "adding a second one.");
            return new GrantOutcome(GrantOutcome.Result.REFUSED, checks, problems,
                    "Refusing to provision: the grant already exists. Nothing was written.",
                    null);
        }

        checks.add("WROTE  grant " + created.id() + " (" + type + ") for " + grantee.value());
        return new GrantOutcome(GrantOutcome.Result.APPLIED, checks, problems,
                "Granted " + type + " to " + grantee.value()
                        + ". Enforcement is unchanged; this grant takes effect only when "
                        + "record-level enforcement is enabled.",
                created.id());
    }

    private void checkEnvironment(GrantRequest r, List<String> checks, List<String> problems) {
        String statedAccount = trim(r.awsAccountId());
        String statedEnvironment = lower(r.environment());

        if (statedAccount == null) {
            problems.add("--aws-account-id is required");
        }
        if (statedEnvironment == null) {
            problems.add("--environment is required");
        }

        /*
         * The operator states an account and an environment; the runtime
         * reports what is actually true. The rules live in
         * RuntimeVerification, shared with the bootstrap utility so the two
         * cannot drift apart.
         *
         * The first version of this compared the flag against itself - the
         * CLI passed the same value as both expected and actual - so it
         * always passed and verified nothing. A flag is an intention, not
         * evidence.
         */
        RuntimeVerification.verify(
                runtime,
                statedAccount,
                statedEnvironment,
                r.confirmed() && !r.dryRun(),
                checks,
                problems);

        if (statedEnvironment != null && !statedEnvironment.equals(expectedEnvironment)) {
            problems.add("environment does not match this build's expectation. Expected "
                    + expectedEnvironment + ", got " + statedEnvironment);
        }
        if ("prod".equals(statedEnvironment) || "production".equals(statedEnvironment)) {
            problems.add("This utility refuses to run against production. "
                    + "Production grants need their own approved procedure.");
        }
    }

    /**
     * Parse {@code --expires-at}, which is optional.
     *
     * <p>Approved decision D-ADM-3: a grant does not expire unless an
     * administrator explicitly sets an expiry, so absence is a deliberate
     * choice. Until now the CLI could not express the other half of that
     * decision at all - it passed null unconditionally - so an expiring grant
     * could only be created through the administrative API, which 404s while
     * enforcement is off.
     *
     * <p><strong>An expiry is not a substitute for revocation.</strong>
     * V087's index predicate is {@code revoked_at IS NULL}, which is not
     * time-aware, so an expired-but-unrevoked grant still occupies its scope
     * and still blocks a replacement. The check below says so rather than
     * letting an operator discover it from a duplicate-key refusal months
     * later.
     */
    private Instant checkExpiry(GrantRequest r, List<String> checks, List<String> problems) {
        String raw = trim(r.expiresAt());
        if (raw == null) {
            checks.add("NOTE   no --expires-at: this grant does not expire (D-ADM-3). "
                    + "It ends when it is revoked, which is audited.");
            return null;
        }
        Instant when;
        try {
            when = Instant.parse(raw);
        } catch (DateTimeParseException e) {
            problems.add("--expires-at must be an ISO-8601 instant such as "
                    + "2027-06-30T00:00:00Z, got '" + raw + "'");
            return null;
        }
        if (!when.isAfter(clock.instant())) {
            problems.add("--expires-at " + raw + " is not in the future. A grant that is "
                    + "already expired on creation would occupy its scope while granting "
                    + "nothing, and would still need revoking before the scope could be "
                    + "granted again.");
            return null;
        }
        checks.add("OK     expires at " + when + ". NOTE V087 constrains UNREVOKED grants, so "
                + "after expiry this grant still occupies its scope until it is revoked.");
        return when;
    }

    private GrantType checkType(GrantRequest r, List<String> checks, List<String> problems) {
        String raw = trim(r.grantType());
        if (raw == null) {
            problems.add("grant type is required: CENTRAL, UNIT, IO or CONTACT_DERIVATION");
            return null;
        }
        try {
            GrantType type = GrantType.valueOf(raw.toUpperCase(Locale.ROOT));
            checks.add("OK     grant type " + type);
            return type;
        } catch (IllegalArgumentException e) {
            problems.add("unknown grant type '" + raw
                    + "'. Valid: CENTRAL, UNIT, IO, CONTACT_DERIVATION");
            return null;
        }
    }

    private InstitutionalIdentifier checkIdentifier(
            String raw, String what, List<String> checks, List<String> problems) {
        String value = trim(raw);
        if (value == null) {
            problems.add(what + " identifier is required and must be supplied explicitly");
            return null;
        }
        if (value.contains("@")) {
            problems.add(what + " looks like an email address. Supply the archive identifier "
                    + "(ARP-XXXXXXXX-XXXXXXXX); an email is not an identity here.");
            return null;
        }
        if (!ARP.matcher(value).matches()) {
            problems.add(what + " is not a well-formed archive identifier "
                    + "(expected ARP-XXXXXXXX-XXXXXXXX)");
            return null;
        }
        checks.add("OK     " + what + " is a well-formed archive identifier " + mask(value));
        return new InstitutionalIdentifier(value);
    }

    private void checkScope(GrantRequest r, GrantType type,
            List<String> checks, List<String> problems) {
        if (type == null) {
            return;
        }
        String unit = trim(r.unitNumber());
        String io = trim(r.ioValue());
        switch (type) {
            case UNIT -> {
                if (unit == null) {
                    problems.add("UNIT grants need --unit-number");
                } else if (!UNIT.matcher(unit).matches()) {
                    problems.add("unit number '" + unit + "' is not a unit number");
                } else {
                    checks.add("OK     unit " + unit
                            + (r.includeDescendants() ? " INCLUDING descendants" : " only, no descendants"));
                }
                if (io != null) {
                    problems.add("--io-value is meaningless for a UNIT grant");
                }
            }
            case IO -> {
                if (io == null) {
                    problems.add("IO grants need --io-value");
                } else {
                    checks.add("OK     IO value " + mask(io)
                            + " (Awards only; an IO grant never reaches Proposals, "
                            + "Subawards or Negotiations)");
                }
                if (unit != null) {
                    problems.add("--unit-number is meaningless for an IO grant");
                }
                if (r.includeDescendants()) {
                    problems.add("--include-descendants is meaningless for an IO grant");
                }
            }
            case CENTRAL, CONTACT_DERIVATION -> {
                if (unit != null || io != null || r.includeDescendants()) {
                    problems.add(type + " grants take no scope; remove --unit-number, "
                            + "--io-value and --include-descendants");
                } else {
                    checks.add("OK     " + type + " takes no scope argument");
                }
            }
        }
    }

    private void checkProvenance(GrantRequest r, List<String> checks, List<String> problems) {
        if (trim(r.operator()) == null) {
            problems.add("--operator is required: the audit row records the person, not the account");
        }
        if (trim(r.reason()) == null) {
            problems.add("--reason is required");
        }
        if (trim(r.approvalReference()) == null) {
            problems.add("--approval-reference is required, so the audit row points at the approval");
        }
        if (problems.isEmpty()) {
            checks.add("OK     operator, reason and approval reference recorded for the audit row");
        }
    }

    private void checkSubjects(GrantRequest r, InstitutionalIdentifier grantee,
            InstitutionalIdentifier actor, GrantType type,
            List<String> checks, List<String> problems) {

        if (adminRoles.findActive(actor).isEmpty()) {
            problems.add("the acting administrator " + mask(actor.value())
                    + " holds no ACTIVE ARCHIVE_ADMIN membership. "
                    + "Grants must be issued by a real administrator.");
        } else {
            checks.add("OK     acting administrator holds an ACTIVE membership");
        }

        /*
         * ARCHIVE_ADMIN confers no research-data access, and this utility
         * must never quietly change that. Granting data access TO an
         * administrator is legitimate - they may also be a researcher - but
         * it is a separate, deliberate act that has to be said out loud.
         */
        if (grantee.equals(actor)) {
            checks.add("NOTE   the grantee IS the acting administrator. ARCHIVE_ADMIN confers "
                    + "no data access, so this grant is what would give them records.");
        }

        List<AccessGrant> existing = grants.findForGrantee(grantee);
        List<AccessGrant> duplicates = existing.stream()
                .filter(g -> g.type() == type)
                .filter(g -> equalScope(g, r))
                .filter(g -> g.revokedAt() == null)
                .toList();
        if (!duplicates.isEmpty()) {
            problems.add("an identical active grant already exists (id "
                    + duplicates.get(0).id() + "). Modify or revoke it rather than adding "
                    + "a second; two active grants with the same scope cannot be told apart "
                    + "when one is later revoked.");
        } else {
            checks.add("OK     no identical active grant exists for this grantee");
        }
    }

    private static boolean equalScope(AccessGrant grant, GrantRequest r) {
        return Objects.equals(trim(grant.unitNumber()), trim(r.unitNumber()))
                && Objects.equals(trim(grant.ioValue()), trim(r.ioValue()))
                && grant.includeDescendants() == r.includeDescendants();
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
        return value.length() <= 6
                ? "****"
                : value.substring(0, 2) + "****" + value.substring(value.length() - 2);
    }
}
