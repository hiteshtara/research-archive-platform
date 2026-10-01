package edu.bu.archive.application.authorization;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;

/**
 * Per-request record authorization. With enforcement OFF every method is a
 * no-op that keeps today's behaviour ("record authorization not enforced").
 * With enforcement ON, a missing identity, policy or store, or any failure,
 * denies.
 */
public class RecordAuthorizationService implements RecordVisibility {

    private static final String OUTCOME_ATTRIBUTE = RecordAuthorizationService.class.getName() + ".outcome";
    private static final ThreadLocal<AccessOutcome> PROPAGATED = new ThreadLocal<>();

    private final AuthorizationProperties properties;
    private final CurrentIdentityProvider identities;
    private final IdentityResolver identityResolver;
    private final AccessScopeResolver scopeResolver;
    private final RecordFactsRepository facts;
    private final UnitHierarchy unitHierarchy;
    private final DescendantUnits descendantUnits;
    private final IoSqlStrategy ioSql;

    /** Expands a unit to itself plus its descendants. */
    public interface DescendantUnits {
        Set<String> selfAndDescendants(String unitNumber);
    }

    public RecordAuthorizationService(
            AuthorizationProperties properties,
            CurrentIdentityProvider identities,
            IdentityResolver identityResolver,
            AccessScopeResolver scopeResolver,
            RecordFactsRepository facts,
            UnitHierarchy unitHierarchy,
            DescendantUnits descendantUnits,
            IoSqlStrategy ioSql
    ) {
        this.properties = Objects.requireNonNull(properties);
        this.identities = Objects.requireNonNull(identities);
        this.identityResolver = Objects.requireNonNull(identityResolver);
        this.scopeResolver = Objects.requireNonNull(scopeResolver);
        this.facts = Objects.requireNonNull(facts);
        this.unitHierarchy = Objects.requireNonNull(unitHierarchy);
        this.descendantUnits = Objects.requireNonNull(descendantUnits);
        this.ioSql = Objects.requireNonNull(ioSql);
    }

    public boolean enforced() {
        return properties.isEnforcementEnabled();
    }

    /** True when the caller may use paths with no record-level scoping yet. */
    @Override
    public boolean unrestricted() {
        return !enforced() || outcome() instanceof AccessOutcome.Scoped scoped && scoped.scope().central();
    }

    /** The current request's outcome; computed once per request. */
    public AccessOutcome outcome() {
        AccessOutcome propagated = PROPAGATED.get();
        if (propagated != null) {
            return propagated;
        }
        RequestAttributes request = RequestContextHolder.getRequestAttributes();
        if (request == null) {
            // Not on a request thread and nothing propagated: fail closed.
            return new AccessOutcome.Denied(AccessOutcome.DenialReason.EVALUATION_FAILED);
        }
        Object cached = request.getAttribute(OUTCOME_ATTRIBUTE, RequestAttributes.SCOPE_REQUEST);
        if (cached instanceof AccessOutcome outcome) {
            return outcome;
        }
        AccessOutcome computed = compute();
        request.setAttribute(OUTCOME_ATTRIBUTE, computed, RequestAttributes.SCOPE_REQUEST);
        return computed;
    }

    private AccessOutcome compute() {
        if (properties.policy().isEmpty()) {
            return new AccessOutcome.Denied(AccessOutcome.DenialReason.POLICY_NOT_CONFIGURED);
        }
        try {
            Optional<ValidatedCognitoIdentity> identity = identities.current();
            if (identity.isEmpty()) {
                return new AccessOutcome.NotProvisioned(AccessOutcome.NotProvisionedReason.NO_IDENTITY_LINK);
            }
            return scopeResolver.resolve(identityResolver.resolve(identity.get()));
        } catch (RuntimeException failure) {
            return new AccessOutcome.Denied(AccessOutcome.DenialReason.EVALUATION_FAILED);
        }
    }

    /** Runs work on another thread with this request's outcome (e.g. Global Search branches). */
    @Override
    public <T> Supplier<T> propagate(Supplier<T> work) {
        if (!enforced()) {
            return work;
        }
        AccessOutcome captured = outcome();
        return () -> {
            AccessOutcome previous = PROPAGATED.get();
            PROPAGATED.set(captured);
            try {
                return work.get();
            } finally {
                if (previous == null) {
                    PROPAGATED.remove();
                } else {
                    PROPAGATED.set(previous);
                }
            }
        };
    }

    /** Throws for a caller who may not use the archive at all. */
    public void requireProvisioned() {
        if (!enforced()) {
            return;
        }
        switch (outcome()) {
            case AccessOutcome.NotProvisioned ignored -> throw new AccessNotProvisionedException();
            case AccessOutcome.Denied ignored -> throw new IdentityAccessDeniedException();
            case AccessOutcome.Scoped ignored -> { }
        }
    }

    /** The scope predicate for a module's row alias (empty when not enforced or Central). */
    public SqlFragment scopeSql(RecordModule module, String alias) {
        if (!enforced()) {
            return SqlFragment.NONE;
        }
        Optional<AuthorizationPolicy> policy = properties.policy();
        if (policy.isEmpty()) {
            return SqlFragment.DENY_ALL;
        }
        AccessOutcome outcome = outcome();
        return new RecordScopeSqlBuilder(policy.get(), ioSql)
                .build(outcome, module, alias, expandedUnits(outcome, policy.get()));
    }

    public void requireAward(long awardId) {
        if (!enforced()) {
            return;
        }
        requireProvisioned();
        String awardNumber = facts.awardNumberForId(awardId).orElseThrow(RecordNotAccessibleException::new);
        requireVersion(facts.awardFamily(awardNumber), String.valueOf(awardId));
    }

    /** Endpoints that read an Award family by number read its current version. */
    public void requireAwardNumber(String awardNumber) {
        if (!enforced()) {
            return;
        }
        requireProvisioned();
        String versionKey = facts.currentAwardVersionKey(awardNumber).orElseThrow(RecordNotAccessibleException::new);
        requireVersion(facts.awardFamily(awardNumber), versionKey);
    }

    public void requireProposal(long proposalId) {
        if (!enforced()) {
            return;
        }
        requireProvisioned();
        String proposalNumber = facts.proposalNumberForId(proposalId).orElseThrow(RecordNotAccessibleException::new);
        requireVersion(facts.proposalFamily(proposalNumber), String.valueOf(proposalId));
    }

    public void requireProposalNumber(String proposalNumber) {
        if (!enforced()) {
            return;
        }
        requireProvisioned();
        String versionKey = facts.latestProposalVersionKey(proposalNumber).orElseThrow(RecordNotAccessibleException::new);
        requireVersion(facts.proposalFamily(proposalNumber), versionKey);
    }

    /** Whether a related record should be listed; never throws. */
    @Override
    public boolean canSeeAwardNumber(String awardNumber) {
        return silently(() -> requireAwardNumber(awardNumber));
    }

    @Override
    public boolean canSeeProposalNumber(String proposalNumber) {
        return silently(() -> requireProposalNumber(proposalNumber));
    }

    @Override
    public boolean canSeeProposal(long proposalId) {
        return silently(() -> requireProposal(proposalId));
    }

    /** Every version of the Proposal family, each decided exactly as {@link #canSeeProposal} would. */
    @Override
    public boolean canSeeEveryProposalVersion(String proposalNumber) {
        if (!enforced()) {
            return true;
        }
        try {
            requireProvisioned();
            return everyVersionAllowed(facts.proposalFamily(proposalNumber));
        } catch (RuntimeException denied) {
            return false;
        }
    }

    @Override
    public boolean canSeeAward(long awardId) {
        return silently(() -> requireAward(awardId));
    }

    /**
     * Every version of the family, each decided exactly as
     * {@link #canSeeAward} would decide it (one family load, not one per
     * version). Under FAMILY_WIDE this is true whenever any version is in
     * scope; under PER_VERSION only when each version is.
     */
    @Override
    public boolean canSeeEveryAwardVersion(String awardNumber) {
        if (!enforced()) {
            return true;
        }
        try {
            requireProvisioned();
            return everyVersionAllowed(facts.awardFamily(awardNumber));
        } catch (RuntimeException denied) {
            return false;
        }
    }

    private boolean everyVersionAllowed(List<RecordFacts> family) {
        if (family.isEmpty()) {
            return false;
        }
        AuthorizationPolicy policy = properties.policy().orElseThrow(IdentityAccessDeniedException::new);
        RecordAccessEvaluator evaluator = new RecordAccessEvaluator(policy, unitHierarchy);
        AccessOutcome outcome = outcome();
        return family.stream().allMatch(version -> evaluator.decide(outcome, version, family).allowed());
    }

    /**
     * For endpoints whose response spans the whole Award family (AI): the
     * current version must be in scope (else 404) AND every version must be
     * visible (else 403 AI_NOT_AVAILABLE_FOR_PARTIAL_ACCESS).
     */
    public void requireEveryAwardVersion(String awardNumber) {
        if (!enforced()) {
            return;
        }
        requireAwardNumber(awardNumber);
        if (!canSeeEveryAwardVersion(awardNumber)) {
            throw new PartialFamilyAccessException();
        }
    }

    private boolean silently(Runnable check) {
        if (!enforced()) {
            return true;
        }
        try {
            check.run();
            return true;
        } catch (RuntimeException denied) {
            return false;
        }
    }

    private void requireVersion(List<RecordFacts> family, String versionKey) {
        RecordFacts version = family.stream()
                .filter(v -> v.versionKey().equals(versionKey))
                .findFirst()
                .orElseThrow(RecordNotAccessibleException::new);
        AuthorizationPolicy policy = properties.policy().orElseThrow(IdentityAccessDeniedException::new);
        AccessDecision decision = new RecordAccessEvaluator(policy, unitHierarchy).decide(outcome(), version, family);
        if (!decision.allowed()) {
            throw new RecordNotAccessibleException();
        }
    }

    /** Units in scope, expanded with descendants only where policy and grant both allow it. */
    private Set<String> expandedUnits(AccessOutcome outcome, AuthorizationPolicy policy) {
        if (!(outcome instanceof AccessOutcome.Scoped scoped)) {
            return Set.of();
        }
        Set<String> units = new LinkedHashSet<>();
        for (UnitGrant unit : scoped.scope().units()) {
            units.add(unit.unitNumber());
            if (policy.departmentMatch() == AuthorizationPolicy.DepartmentMatch.LEAD_UNIT_WITH_DESCENDANTS
                    && unit.includeDescendants()) {
                units.addAll(descendantUnits.selfAndDescendants(unit.unitNumber()));
            }
        }
        return units;
    }

    /** A privacy-safe description of the caller's access, for the UI. No identifiers. */
    public AccessStatus status() {
        if (!enforced()) {
            return new AccessStatus("NOT_ENFORCED", RecordAuthorizationGate.NOT_ENFORCED_LABEL, null, List.of());
        }
        return switch (outcome()) {
            case AccessOutcome.NotProvisioned notProvisioned ->
                    new AccessStatus("ENFORCED", "record authorization enforced", AccessNotProvisionedProblem.CODE, List.of());
            case AccessOutcome.Denied denied ->
                    new AccessStatus("ENFORCED", "record authorization enforced", "ACCESS_DENIED", List.of());
            case AccessOutcome.Scoped scoped -> new AccessStatus("ENFORCED", "record authorization enforced", null,
                    grantKinds(scoped.scope()));
        };
    }

    private static List<String> grantKinds(AccessScope scope) {
        List<String> kinds = new java.util.ArrayList<>();
        if (scope.central()) kinds.add("CENTRAL");
        if (!scope.units().isEmpty()) kinds.add("DEPARTMENT");
        if (scope.contactPersonId().isPresent()) kinds.add("RESEARCH_STAFF");
        if (!scope.ioValues().isEmpty()) kinds.add("OTHER_AUTHORIZED_VIEWER");
        return kinds;
    }

    public record AccessStatus(String mode, String label, String problem, List<String> grantKinds) {
    }

    public static <T> List<T> keep(Collection<T> items, java.util.function.Predicate<T> allowed) {
        return items.stream().filter(allowed).toList();
    }
}
