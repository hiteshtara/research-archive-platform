package edu.bu.archive.application.authorization.admin.provisioning;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

import edu.bu.archive.application.authorization.AccessGrant;
import edu.bu.archive.application.authorization.AccessGrantRepository;
import edu.bu.archive.application.authorization.GrantType;
import edu.bu.archive.application.authorization.InstitutionalIdentifier;
import edu.bu.archive.application.authorization.admin.AdminRoleRepository;
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
    private final ArchiveAdminService admin;
    private final AdminRoleRepository adminRoles;
    private final AccessGrantRepository grants;

    public GrantProvisioningCommand(
            String expectedAccountId, String expectedEnvironment,
            ArchiveAdminService admin, AdminRoleRepository adminRoles,
            AccessGrantRepository grants) {
        this.expectedAccountId = Objects.requireNonNull(expectedAccountId);
        this.expectedEnvironment = expectedEnvironment.toLowerCase(Locale.ROOT);
        this.admin = Objects.requireNonNull(admin);
        this.adminRoles = Objects.requireNonNull(adminRoles);
        this.grants = Objects.requireNonNull(grants);
    }

    public GrantOutcome run(GrantRequest request) {
        List<String> checks = new ArrayList<>();
        List<String> problems = new ArrayList<>();

        checkEnvironment(request, checks, problems);
        GrantType type = checkType(request, checks, problems);
        InstitutionalIdentifier grantee = checkIdentifier(
                request.granteeIdentifier(), "grantee", checks, problems);
        InstitutionalIdentifier actor = checkIdentifier(
                request.actorIdentifier(), "acting administrator", checks, problems);
        checkScope(request, type, checks, problems);
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
        AccessGrant created = admin.grantAccess(actor, grantee,
                new GrantSpecification(type, trim(request.unitNumber()),
                        request.includeDescendants(), trim(request.ioValue()),
                        null,
                        "operator=" + request.operator().trim()
                                + "; approval=" + request.approvalReference().trim()
                                + "; " + request.reason().trim()));

        checks.add("WROTE  grant " + created.id() + " (" + type + ") for " + grantee.value());
        return new GrantOutcome(GrantOutcome.Result.APPLIED, checks, problems,
                "Granted " + type + " to " + grantee.value()
                        + ". Enforcement is unchanged; this grant takes effect only when "
                        + "record-level enforcement is enabled.",
                created.id());
    }

    private void checkEnvironment(GrantRequest r, List<String> checks, List<String> problems) {
        compare("AWS account", trim(r.awsAccountId()), expectedAccountId, checks, problems);
        compare("environment", lower(r.environment()), expectedEnvironment, checks, problems);
        /*
         * A separate, unconditional refusal. The comparison above already
         * fails for production, but only if the utility happens to be
         * configured for dev; this refuses regardless of configuration.
         */
        if ("prod".equals(expectedEnvironment) || "production".equals(expectedEnvironment)) {
            problems.add("This utility refuses to run against production. "
                    + "Production grants need their own approved procedure.");
        }
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

    private void compare(String what, String actual, String expected,
            List<String> checks, List<String> problems) {
        if (actual == null) {
            problems.add(what + " is required");
        } else if (!actual.equals(expected)) {
            problems.add(what + " does not match this environment. Expected " + expected
                    + ", got " + actual);
        } else {
            checks.add("OK     " + what + " matches (" + expected + ")");
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
        return value.length() <= 6
                ? "****"
                : value.substring(0, 2) + "****" + value.substring(value.length() - 2);
    }
}
