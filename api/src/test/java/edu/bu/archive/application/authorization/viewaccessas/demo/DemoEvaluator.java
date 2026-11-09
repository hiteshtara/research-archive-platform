package edu.bu.archive.application.authorization.viewaccessas.demo;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import edu.bu.archive.application.authorization.AccessGrant;
import edu.bu.archive.application.authorization.AccessOutcome;
import edu.bu.archive.application.authorization.AccessScopeResolver;
import edu.bu.archive.application.authorization.AuthorizationPolicy;
import edu.bu.archive.application.authorization.AuthorizationProperties;
import edu.bu.archive.application.authorization.IdentityEnrollment;
import edu.bu.archive.application.authorization.IdentityLinkStatus;
import edu.bu.archive.application.authorization.IdentityResolver;
import edu.bu.archive.application.authorization.IoSqlStrategy;
import edu.bu.archive.application.authorization.PartialFamilyAccessException;
import edu.bu.archive.application.authorization.RecordAuthorizationService;
import edu.bu.archive.application.authorization.RecordFacts;
import edu.bu.archive.application.authorization.RecordModule;
import edu.bu.archive.application.authorization.RecordScopeSqlBuilder;
import edu.bu.archive.application.authorization.SqlFragment;
import edu.bu.archive.application.authorization.ValidatedCognitoIdentity;
import edu.bu.archive.application.authorization.fixtures.InMemoryAccessGrantRepository;
import edu.bu.archive.application.authorization.fixtures.InMemoryIdentityLinkRepository;
import edu.bu.archive.application.authorization.viewaccessas.SyntheticProfile;
import edu.bu.archive.application.authorization.viewaccessas.ViewAccessAsHarness;

/**
 * Answers the demonstration's questions using the production authorization
 * code, and nothing else.
 *
 * <p>TEST SCOPE ONLY. There is deliberately not one {@code if} statement in
 * this file about units, roles, modules, descendants or versions. Every
 * verdict below is produced by one of three production components:
 *
 * <ol>
 *   <li>{@link ViewAccessAsHarness} — composing the real
 *       {@code AccessScopeResolver} and {@code RecordAccessEvaluator}, for the
 *       per-version PERMIT/DENY grid and the rule that caused it;
 *   <li>{@link RecordAuthorizationService} with <b>enforcement ON inside this
 *       object only</b>, for the family-spanning AI restriction and for
 *       Subaward and Negotiation inheritance, which the evaluator alone cannot
 *       answer because inheritance is resolved through the facts port;
 *   <li>{@link RecordScopeSqlBuilder} — the real list-scoping SQL, rendered
 *       per profile and module.
 * </ol>
 *
 * <h2>Why enforcement ON here is not enforcement ON in DEV</h2>
 *
 * The {@link AuthorizationProperties} instance is constructed inside
 * {@link #serviceFor}, lives as long as one evaluation, and is never read from
 * or written to Spring configuration. DEV's deployed task definition carries no
 * {@code APP_AUTHORIZATION_*} variable and this code never runs there: it is
 * test scope, and {@code ViewAccessAsAbsentFromJarTest} asserts it is absent
 * from the artifact. Enforcement ON is also the only way to reach the AI gate —
 * with the flag off every method short circuits to today's permissive
 * behaviour, so a demonstration built on the flag-off path would report PERMIT
 * for everything and look like it worked.
 *
 * <h2>Why the identity is not a user</h2>
 *
 * {@link ValidatedCognitoIdentity} is built from the synthetic issuer
 * {@code simulation://view-access-as/not-a-user-pool}. No token is minted or
 * parsed, nothing passes through {@code SecurityConfiguration}, and the subject
 * is not a pool subject. There is nothing here to authenticate as, which is the
 * difference between simulation and impersonation.
 */
public final class DemoEvaluator {

    /** Not a user pool, and asserted not to look like one. */
    static final String SYNTHETIC_ISSUER = "simulation://view-access-as/not-a-user-pool";

    /** The institutional identity every profile is keyed on. Never enrolled. */
    private static final String GRANTEE = SyntheticProfile.GRANTEE.value();

    /** A fixed evaluation instant, so a verdict does not change under the reader. */
    public static final Instant EVALUATED_AT = Instant.parse("2026-11-09T12:00:00Z");

    private final DemoModel.DemoFacts facts = new DemoModel.DemoFacts();

    // ── what the browser receives ───────────────────────────────────────

    public record VersionVerdict(String versionKey, String verdict, String rule) {
    }

    public record FamilyResult(
            String familyKey,
            String label,
            String leadUnit,
            List<VersionVerdict> versions,
            int permitted,
            int denied,
            boolean partialFamily,
            boolean changesUnderOtherScope,
            String otherScope,
            String aiVerdict,
            String aiRule) {
    }

    public record ModuleResult(long id, String label, String verdict, String rule) {
    }

    public record ProfileResult(
            String profile,
            String description,
            List<String> grants,
            String outcome,
            List<FamilyResult> awards,
            List<ModuleResult> subawards,
            List<ModuleResult> negotiations,
            Map<String, String> renderedSql) {
    }

    public record Settings(String versionScope, String departmentMatch) {

        public AuthorizationPolicy toPolicy() {
            return new AuthorizationPolicy(
                    AuthorizationPolicy.VersionScope.valueOf(versionScope),
                    AuthorizationPolicy.DepartmentMatch.valueOf(departmentMatch),
                    DemoModel.RESEARCH_STAFF_ROLES);
        }
    }

    // ── evaluation ──────────────────────────────────────────────────────

    public ProfileResult evaluate(SyntheticProfile profile, Settings settings) {
        AuthorizationPolicy policy = settings.toPolicy();
        var harness = new ViewAccessAsHarness(policy, DemoModel.unitHierarchy(), EVALUATED_AT);
        RecordAuthorizationService service = serviceFor(profile, policy);

        var attributes = new ServletRequestAttributes(new MockHttpServletRequest());
        RequestContextHolder.setRequestAttributes(attributes);
        try {
            if (!service.enforced()) {
                /*
                 * The trap this guards is the one the harness's design note
                 * names: with enforcement off every require/canSee below
                 * returns early and reports access for everything. Refusing
                 * to render is better than rendering a lie.
                 */
                throw new IllegalStateException(
                        "enforcement is off inside the simulated service - every verdict "
                                + "below would be vacuous");
            }
            return new ProfileResult(
                    profile.name(),
                    profile.description(),
                    describeGrants(profile),
                    describeOutcome(harness.outcomeFor(profile)),
                    awardResults(profile, harness, service),
                    subawardResults(service),
                    negotiationResults(service),
                    renderedSql(harness.outcomeFor(profile), policy));
        } finally {
            RequestContextHolder.resetRequestAttributes();
        }
    }

    private List<FamilyResult> awardResults(
            SyntheticProfile profile, ViewAccessAsHarness harness, RecordAuthorizationService service) {

        /*
         * The same profile under the OPPOSITE version scope, so the page can
         * show whether this family's verdicts depend on that policy at all.
         *
         * This is the clearest way to see what SEC-003 actually did.
         * Contact-derived access is now decided against the version being
         * read NO MATTER what versionScope says - the business decided that
         * rule rather than delegating it - while a department or IO grant
         * still follows the policy. So a research-staff family reads
         * "unchanged" here and an IO family reads "changes", and neither
         * answer is written down anywhere: both come from running the engine
         * twice.
         */
        AuthorizationPolicy flipped = flipVersionScope(harness.policy());
        var otherHarness = new ViewAccessAsHarness(flipped, DemoModel.unitHierarchy(), EVALUATED_AT);

        List<FamilyResult> results = new ArrayList<>();
        for (DemoModel.Family family : DemoModel.families()) {
            List<VersionVerdict> versions = new ArrayList<>();
            int permitted = 0;
            for (RecordFacts version : family.versions()) {
                ViewAccessAsHarness.Evaluation evaluation =
                        harness.evaluate(profile, version, family.versions());
                versions.add(new VersionVerdict(
                        version.versionKey(),
                        evaluation.verdict().name(),
                        evaluation.rule()));
                if (evaluation.verdict().name().equals("PERMIT")) {
                    permitted++;
                }
            }

            /*
             * The AI restriction, from the real service. requireEveryAwardVersion
             * is what Award Summary, Award Questions and the history endpoints
             * call; its refusal code is the engine's, not this file's.
             */
            String aiVerdict;
            String aiRule;
            try {
                service.requireEveryAwardVersion(family.familyKey());
                aiVerdict = "ALLOWED";
                aiRule = "every version visible";
            } catch (PartialFamilyAccessException e) {
                aiVerdict = "REFUSED";
                aiRule = PartialFamilyAccessException.CODE;
            } catch (edu.bu.archive.application.authorization.RecordNotAccessibleException e) {
                /*
                 * requireEveryAwardVersion reads the CURRENT version first, so
                 * a contact who is not on it fails here and never reaches the
                 * partial-access check. The exception is a NoSuchElementException
                 * by design (D-J / P7): the caller gets the same 404 as for a
                 * record that does not exist, so a direct URL never confirms
                 * that an out-of-scope record is there.
                 */
                aiVerdict = "REFUSED";
                aiRule = "404 - current version not visible; indistinguishable from absent";
            } catch (RuntimeException e) {
                aiVerdict = "REFUSED";
                aiRule = e.getClass().getSimpleName();
            }

            List<String> underOtherScope = family.versions().stream()
                    .map(version -> otherHarness.evaluate(profile, version, family.versions())
                            .verdict().name())
                    .toList();
            List<String> here = versions.stream().map(VersionVerdict::verdict).toList();

            int denied = family.versions().size() - permitted;
            results.add(new FamilyResult(
                    family.familyKey(), family.label(), family.leadUnit(), versions,
                    permitted, denied,
                    permitted > 0 && denied > 0,
                    !here.equals(underOtherScope),
                    flipped.versionScope().name(),
                    aiVerdict, aiRule));
        }
        return results;
    }

    private static AuthorizationPolicy flipVersionScope(AuthorizationPolicy policy) {
        var other = policy.versionScope() == AuthorizationPolicy.VersionScope.PER_VERSION
                ? AuthorizationPolicy.VersionScope.FAMILY_WIDE
                : AuthorizationPolicy.VersionScope.PER_VERSION;
        return new AuthorizationPolicy(
                other, policy.departmentMatch(), policy.researchStaffRoles(),
                policy.contactDerivation());
    }

    private List<ModuleResult> subawardResults(RecordAuthorizationService service) {
        List<ModuleResult> results = new ArrayList<>();
        for (DemoModel.Subaward subaward : DemoModel.subawards()) {
            boolean visible = service.canSeeSubaward(subaward.subawardId());
            results.add(new ModuleResult(
                    subaward.subawardId(), subaward.label(),
                    visible ? "PERMIT" : "DENY",
                    "inherited through subaward_funding.award_id"));
        }
        return results;
    }

    private List<ModuleResult> negotiationResults(RecordAuthorizationService service) {
        List<ModuleResult> results = new ArrayList<>();
        for (DemoModel.Negotiation negotiation : DemoModel.negotiations()) {
            boolean visible = service.canSeeNegotiation(negotiation.negotiationId());
            results.add(new ModuleResult(
                    negotiation.negotiationId(), negotiation.label(),
                    visible ? "PERMIT" : "DENY",
                    negotiation.awardNumber() != null
                            ? "association type Award -> " + negotiation.awardNumber()
                            : negotiation.leadUnitNumber() != null
                                    ? "lead unit " + negotiation.leadUnitNumber()
                                    : "no lead unit and no association"));
        }
        return results;
    }

    /**
     * The real list-scoping SQL for each module, rendered from the profile's
     * resolved scope.
     *
     * <p>Rendered, not executed: there is no database in this demonstration, so
     * what is shown is the predicate production would put in the {@code WHERE}
     * clause. Executing it against seeded rows is the next stage, and the
     * existing {@code *RecordAuthorizationIntegrationTest} suites already do
     * exactly that against a real PostgreSQL.
     */
    private Map<String, String> renderedSql(AccessOutcome outcome, AuthorizationPolicy policy) {
        var builder = new RecordScopeSqlBuilder(policy, IoSqlStrategy.NONE);
        Map<String, String> rendered = new LinkedHashMap<>();
        for (RecordModule module : RecordModule.values()) {
            SqlFragment fragment = builder.build(
                    outcome, module, aliasFor(module), expandedUnits(outcome));
            String sql = fragment.sql().isBlank() ? "(no predicate - unscoped)" : fragment.sql().trim();
            rendered.put(module.name(), sql + paramSuffix(fragment));
        }
        return rendered;
    }

    private static String paramSuffix(SqlFragment fragment) {
        return fragment.params().isEmpty() ? "" : "\n-- parameters: " + fragment.params();
    }

    private static String aliasFor(RecordModule module) {
        return switch (module) {
            case AWARD -> "av";
            case PROPOSAL -> "pv";
            case SUBAWARD -> "s";
            case NEGOTIATION -> "n";
            case IRB -> "irb";
        };
    }

    /**
     * The expanded unit set the production caller passes in. The real
     * expansion is a recursive unit query; here the hierarchy is the synthetic
     * map, and the policy decides whether expansion is honoured at all.
     */
    private static Set<String> expandedUnits(AccessOutcome outcome) {
        Set<String> units = new java.util.LinkedHashSet<>();
        if (outcome instanceof AccessOutcome.Scoped scoped) {
            scoped.scope().units().forEach(grant -> {
                units.add(grant.unitNumber());
                if (grant.includeDescendants() && grant.unitNumber().equals(DemoModel.DEPARTMENT_UNIT)) {
                    units.add(DemoModel.CHILD_UNIT);
                }
            });
        }
        return units;
    }

    // ── wiring ──────────────────────────────────────────────────────────

    /**
     * A real {@link RecordAuthorizationService}, enforcement on, over this
     * profile's grants and the synthetic facts.
     */
    private RecordAuthorizationService serviceFor(SyntheticProfile profile, AuthorizationPolicy policy) {
        var links = new InMemoryIdentityLinkRepository();
        var grants = new InMemoryAccessGrantRepository();

        var identity = new ValidatedCognitoIdentity(
                SYNTHETIC_ISSUER, "simulated-" + profile.name(), "simulated", EVALUATED_AT);
        links.link(identity, GRANTEE,
                profile.kualiPersonId().map(id -> id.value()).orElse(null),
                "simulated", IdentityLinkStatus.ACTIVE);
        profile.grants().forEach(grants::add);

        var properties = new AuthorizationProperties();
        properties.setEnforcementEnabled(true);
        properties.setVersionScope(policy.versionScope());
        properties.setDepartmentMatch(policy.departmentMatch());
        properties.setResearchStaffRoles(policy.researchStaffRoles());
        properties.setContactDerivation(policy.contactDerivation());
        properties.setMaxSignInAge(Duration.ofHours(8));

        Clock clock = Clock.fixed(EVALUATED_AT, ZoneOffset.UTC);
        return new RecordAuthorizationService(
                properties,
                () -> Optional.of(identity),
                new IdentityResolver(links),
                new AccessScopeResolver(grants, clock),
                facts,
                DemoModel.unitHierarchy(),
                unit -> expandUnit(unit, policy),
                IoSqlStrategy.NONE,
                IdentityEnrollment.DISABLED,
                clock);
    }

    private static Set<String> expandUnit(String unit, AuthorizationPolicy policy) {
        if (policy.departmentMatch() == AuthorizationPolicy.DepartmentMatch.LEAD_UNIT_WITH_DESCENDANTS
                && DemoModel.DEPARTMENT_UNIT.equals(unit)) {
            return Set.of(unit, DemoModel.CHILD_UNIT);
        }
        return Set.of(unit);
    }

    private static List<String> describeGrants(SyntheticProfile profile) {
        List<String> described = new ArrayList<>();
        for (AccessGrant grant : profile.grants()) {
            StringBuilder text = new StringBuilder(grant.type().name());
            if (grant.unitNumber() != null) {
                text.append(" unit=").append(grant.unitNumber())
                        .append(" includeDescendants=").append(grant.includeDescendants());
            }
            if (grant.ioValue() != null) {
                text.append(" io=").append(grant.ioValue());
            }
            described.add(text.toString());
        }
        profile.kualiPersonId().ifPresent(id -> described.add("Kuali principal " + id.value()));
        return described.isEmpty() ? List.of("(no grants)") : described;
    }

    private static String describeOutcome(AccessOutcome outcome) {
        if (outcome instanceof AccessOutcome.Scoped scoped) {
            var scope = scoped.scope();
            return "Scoped: central=" + scope.central()
                    + " units=" + scope.units().size()
                    + " ioValues=" + scope.ioValues().size()
                    + " contactPerson=" + scope.contactPersonId().map(id -> id.value()).orElse("none");
        }
        if (outcome instanceof AccessOutcome.Denied denied) {
            return "Denied: " + denied.reason();
        }
        return outcome.getClass().getSimpleName();
    }
}
