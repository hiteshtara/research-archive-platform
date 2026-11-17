package edu.bu.archive.application.authorization;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
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
public class RecordAuthorizationService
        implements RecordVisibility, AwardRecordAccess, ProposalRecordAccess, SubawardRecordAccess,
        NegotiationRecordAccess {

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
    private final edu.bu.archive.application.authorization.audit.ArchiveActivityRecorder activity;
    private final IdentityEnrollment enrollment;
    private final Clock clock;

    /** A sign-in time this far in the future is accepted as clock skew; beyond it, refused. */
    static final Duration ALLOWED_CLOCK_SKEW = Duration.ofMinutes(5);

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
        this(properties, identities, identityResolver, scopeResolver, facts, unitHierarchy, descendantUnits, ioSql,
                IdentityEnrollment.DISABLED);
    }

    public RecordAuthorizationService(
            AuthorizationProperties properties,
            CurrentIdentityProvider identities,
            IdentityResolver identityResolver,
            AccessScopeResolver scopeResolver,
            RecordFactsRepository facts,
            UnitHierarchy unitHierarchy,
            DescendantUnits descendantUnits,
            IoSqlStrategy ioSql,
            IdentityEnrollment enrollment
    ) {
        this(properties, identities, identityResolver, scopeResolver, facts, unitHierarchy, descendantUnits, ioSql,
                enrollment, Clock.systemUTC());
    }

    public RecordAuthorizationService(
            AuthorizationProperties properties,
            CurrentIdentityProvider identities,
            IdentityResolver identityResolver,
            AccessScopeResolver scopeResolver,
            RecordFactsRepository facts,
            UnitHierarchy unitHierarchy,
            DescendantUnits descendantUnits,
            IoSqlStrategy ioSql,
            IdentityEnrollment enrollment,
            Clock clock
    ) {
        this(properties, identities, identityResolver, scopeResolver, facts, unitHierarchy,
                descendantUnits, ioSql, enrollment, clock,
                edu.bu.archive.application.authorization.audit.ArchiveActivityRecorder
                        .disabled(clock));
    }

    /**
     * @param activity SEC-008. Defaults to a disabled recorder in every other
     *                 constructor, so wiring the call sites changes nothing
     *                 until collection is switched on and the database
     *                 privilege is granted.
     */
    public RecordAuthorizationService(
            AuthorizationProperties properties,
            CurrentIdentityProvider identities,
            IdentityResolver identityResolver,
            AccessScopeResolver scopeResolver,
            RecordFactsRepository facts,
            UnitHierarchy unitHierarchy,
            DescendantUnits descendantUnits,
            IoSqlStrategy ioSql,
            IdentityEnrollment enrollment,
            Clock clock,
            edu.bu.archive.application.authorization.audit.ArchiveActivityRecorder activity
    ) {
        this.activity = Objects.requireNonNull(activity);
        this.clock = Objects.requireNonNull(clock);
        this.enrollment = Objects.requireNonNull(enrollment);
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
            if (properties.isEnforcementEnabled()) {
                // Checked before enrollment and before any grant is read: a refreshed token
                // keeps its original auth_time, so this bounds how long one BU sign-in lasts.
                if (!properties.hasMaxSignInAge()) {
                    return new AccessOutcome.Denied(AccessOutcome.DenialReason.POLICY_NOT_CONFIGURED);
                }
                if (!signInFresh(identity.get().signInTime().orElse(null), properties.getMaxSignInAge())) {
                    return new AccessOutcome.Denied(AccessOutcome.DenialReason.REAUTHENTICATION_REQUIRED);
                }
            }
            // Enrollment (link or re-check) runs once per request, before the identity is
            // resolved, and only while enforcement AND enrollment are both on.
            if (properties.isEnforcementEnabled() && properties.getEnrollment().isEnabled()) {
                EnrollmentOutcome enrolled = enrollment.prepare(identity.get());
                if (enrolled != null && enrolled.deniesRequest()) {
                    // The sign-in session could not be re-verified: fail closed for this request.
                    return new AccessOutcome.Denied(AccessOutcome.DenialReason.EVALUATION_FAILED);
                }
            }
            return scopeResolver.resolve(identityResolver.resolve(identity.get()));
        } catch (RuntimeException failure) {
            return new AccessOutcome.Denied(AccessOutcome.DenialReason.EVALUATION_FAILED);
        }
    }

    private boolean signInFresh(Instant signedIn, Duration maxAge) {
        if (signedIn == null) {
            return false;
        }
        Instant now = clock.instant();
        return !signedIn.isAfter(now.plus(ALLOWED_CLOCK_SKEW)) && !signedIn.plus(maxAge).isBefore(now);
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
            case AccessOutcome.Denied denied -> {
                if (denied.reason() == AccessOutcome.DenialReason.REAUTHENTICATION_REQUIRED) {
                    throw new ReauthenticationRequiredException();
                }
                throw new IdentityAccessDeniedException();
            }
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

    /**
     * {@inheritDoc}
     *
     * <p>Exactly {@link #scopeSql(RecordModule, String)}: NONE when
     * enforcement is off or the caller is Central, the module's predicate
     * otherwise. There is deliberately no second rule for related rows.
     */
    @Override
    public SqlFragment relatedScopeSql(RecordModule module, String alias) {
        return scopeSql(module, alias);
    }

    /**
     * {@inheritDoc}
     *
     * <p>Delegates to {@link #scopeSql(RecordModule, String)} for
     * {@link RecordModule#AWARD}; the Award service depends on the narrow
     * {@link AwardRecordAccess} port rather than on this whole class.
     */
    @Override
    public SqlFragment awardScopeSql(String alias) {
        return scopeSql(RecordModule.AWARD, alias);
    }

    @Override
    public void requireAward(long awardId) {
        if (!enforced()) {
            auditUnenforcedView(RecordModule.AWARD, () -> facts.awardNumberForId(awardId), awardId);
            return;
        }
        AuditTarget target = new AuditTarget(RecordModule.AWARD, null, awardId);
        audited(target, () -> checkAward(awardId, target));
    }

    private void checkAward(long awardId, AuditTarget target) {
        requireProvisioned();
        String awardNumber = facts.awardNumberForId(awardId).orElseThrow(RecordNotAccessibleException::new);
        if (target != null) {
            target.key = awardNumber;
        }
        requireVersion(facts.awardFamily(awardNumber), String.valueOf(awardId),
                RecordAccessEvaluator.DecisionContext.DIRECT, target);
    }

    /** Endpoints that read an Award family by number read its current version. */
    @Override
    public void requireAwardNumber(String awardNumber) {
        if (!enforced()) {
            auditUnenforcedView(RecordModule.AWARD,
                    () -> facts.currentAwardVersionKey(awardNumber).map(version -> awardNumber), null);
            return;
        }
        AuditTarget target = new AuditTarget(RecordModule.AWARD, awardNumber, null);
        audited(target, () -> checkAwardNumber(awardNumber, target));
    }

    private void checkAwardNumber(String awardNumber, AuditTarget target) {
        requireProvisioned();
        String versionKey = facts.currentAwardVersionKey(awardNumber).orElseThrow(RecordNotAccessibleException::new);
        requireVersion(facts.awardFamily(awardNumber), versionKey,
                RecordAccessEvaluator.DecisionContext.DIRECT, target);
    }

    /**
     * {@inheritDoc}
     *
     * <p>Delegates to {@link #scopeSql(RecordModule, String)} for
     * {@link RecordModule#SUBAWARD}; the Subaward services depend on the
     * narrow {@link SubawardRecordAccess} port rather than this whole class.
     */
    @Override
    public SqlFragment subawardScopeSql(String alias) {
        return scopeSql(RecordModule.SUBAWARD, alias);
    }

    /**
     * A Subaward is accessible when ANY funding Award of ANY version in its
     * FAMILY is - the revised rule, approved 2026-10-26 after profiling.
     *
     * <p>The first rule was per version. Real data killed it: funding rows
     * sit almost only on a family's latest version, so a non-Central caller
     * could reach 4.06% of version rows. Family-level takes that to 98.09%.
     *
     * <p>Each funding Award is decided by exactly the same code that decides
     * the Award endpoints ({@link #canSeeAward}), so department grants,
     * descendant units and Research Staff contact access need no Subaward
     * equivalent. A Subaward with no resolvable funding Award has an empty
     * list here and is denied, which is the approved behaviour for both the
     * no-funding and the broken-reference cases - the repository filters
     * unresolvable references out, so a dangling award_id cannot authorise.
     *
     * <p>Reaching a Subaward this way grants nothing on the Awards: the
     * Award endpoints keep their own gates, so a user who gets here through
     * Award A still cannot open Award B that also funds it.
     */
    @Override
    public void requireSubaward(long subawardId) {
        if (!enforced()) {
            auditUnenforcedView(RecordModule.SUBAWARD, () -> facts.subawardCodeForId(subawardId), subawardId);
            return;
        }
        AuditTarget target = new AuditTarget(RecordModule.SUBAWARD, null, subawardId);
        audited(target, () -> {
            requireProvisioned();
            if (activity.enabled()) {
                /*
                 * SEC-008 only: the business key for the row, resolved only
                 * with collection on so collection off runs no extra query.
                 * A Subaward that does not exist was going to 404 in the
                 * service; it 404s here instead, with the same exception,
                 * rather than being recorded as a view of nothing.
                 */
                target.key = facts.subawardCodeForId(subawardId)
                        .orElseThrow(RecordNotAccessibleException::new);
            }
            if (!canSeeSubaward(subawardId)) {
                target.denialCode = "NO_VISIBLE_FUNDING_AWARD";
                throw new RecordNotAccessibleException();
            }
            target.reason = centralCaller() ? AccessReason.CENTRAL.name() : "FUNDING_AWARD";
        });
    }

    @Override
    public boolean canSeeSubaward(long subawardId) {
        if (unrestricted()) {
            /*
             * Central, or enforcement off. Not merely a shortcut: the SQL
             * side renders SqlFragment.NONE for a Central caller, so every
             * Subaward is listed - including one with no funding Award at
             * all. Deciding this one through the funding inheritance would
             * answer false for exactly those rows, and Central would see a
             * Subaward on the page that 404s when opened. D3 gives Central
             * the archive-wide view; the two paths have to say so together.
             */
            return true;
        }
        try {
            /*
             * canSeeAwardForInheritance, not canSeeAward: SEC-005 restricts
             * PARENT_AWARD grants to Award records, so a funding Award that
             * is visible ONLY through such a grant does not authorise this
             * Subaward. Every other grant type behaves exactly as before.
             */
            return facts.subawardFamilyFundingAwardIds(subawardId).stream()
                    .anyMatch(this::canSeeAwardForInheritance);
        } catch (RuntimeException failure) {
            // Fail closed: an error resolving funding is not permission.
            return false;
        }
    }

    @Override
    public SqlFragment negotiationScopeSql(String alias) {
        return scopeSql(RecordModule.NEGOTIATION, alias);
    }

    /**
     * The approved hybrid rule, 2026-10-27. Three ALTERNATIVE paths: the
     * recorded lead unit, an explicitly Award-typed association, or an
     * explicitly Institutional-Proposal-typed association. Any one suffices.
     *
     * <p>The association is never resolved from
     * {@code associated_document_id} alone - {@code negotiationFacts} gates
     * it on the association type, because 6,073 of 10,775 identifiers in
     * dev match more than one record kind and every such match on an
     * unassociated Negotiation is a coincidence.
     *
     * <p>Subaward-typed associations (16 rows) grant nothing: no reliable
     * key exists for them. They reach the lead-unit path like any other.
     *
     * <p>Reaching a Negotiation grants nothing on the associated record
     * itself - that keeps its own gate.
     */
    @Override
    public void requireNegotiation(long negotiationId) {
        if (!enforced()) {
            auditUnenforcedView(RecordModule.NEGOTIATION,
                    () -> facts.negotiationFacts(negotiationId).map(found -> String.valueOf(negotiationId)),
                    null);
            return;
        }
        AuditTarget target = new AuditTarget(RecordModule.NEGOTIATION, String.valueOf(negotiationId), null);
        audited(target, () -> {
            requireProvisioned();
            if (!canSeeNegotiation(negotiationId)) {
                target.denialCode = "NO_NEGOTIATION_BASIS";
                throw new RecordNotAccessibleException();
            }
            target.reason = centralCaller() ? AccessReason.CENTRAL.name() : "NEGOTIATION_BASIS";
        });
    }

    @Override
    public boolean canSeeNegotiation(long negotiationId) {
        if (unrestricted()) {
            // Central, or enforcement off. Central sees every Negotiation,
            // including the 54 with no authorization basis at all.
            return true;
        }
        try {
            Optional<RecordFactsRepository.NegotiationFacts> facts0 =
                    facts.negotiationFacts(negotiationId);
            if (facts0.isEmpty()) {
                return false;
            }
            RecordFactsRepository.NegotiationFacts negotiation = facts0.get();

            if (negotiation.leadUnitNumber() != null
                    && !negotiation.leadUnitNumber().isBlank()
                    && leadUnitInScope(negotiation.leadUnitNumber())) {
                return true;
            }
            if (negotiation.awardNumber() != null
                    && canSeeAwardNumberForInheritance(negotiation.awardNumber())) {
                return true;
            }
            return negotiation.proposalNumber() != null
                    && canSeeProposalNumber(negotiation.proposalNumber());
        } catch (RuntimeException failure) {
            // Fail closed: an error resolving the basis is not permission.
            return false;
        }
    }

    /**
     * Whether a unit is within the caller's granted units, expanded for
     * descendants exactly as the SQL predicate expands them - so the two
     * paths cannot drift on hierarchy behaviour.
     */
    private boolean leadUnitInScope(String leadUnitNumber) {
        AccessOutcome outcome = outcome();
        if (!(outcome instanceof AccessOutcome.Scoped scoped)) {
            return false;
        }
        Optional<AuthorizationPolicy> policy = properties.policy();
        if (policy.isEmpty()) {
            return false;
        }
        return expandedUnits(outcome, policy.get()).contains(leadUnitNumber.trim());
    }

    @Override
    public SqlFragment proposalScopeSql(String alias) {
        return scopeSql(RecordModule.PROPOSAL, alias);
    }

    /**
     * Family-wide Proposal responses require every version to be visible -
     * the same rule the Award family uses, and for the same reason: a subset
     * presented as the whole family is a wrong answer, not a partial one.
     */
    @Override
    public void requireEveryProposalVersion(String proposalNumber) {
        if (!enforced()) {
            auditUnenforcedView(RecordModule.PROPOSAL,
                    () -> facts.latestProposalVersionKey(proposalNumber).map(version -> proposalNumber), null);
            return;
        }
        AuditTarget target = new AuditTarget(RecordModule.PROPOSAL, proposalNumber, null);
        audited(target, () -> {
            checkProposalNumber(proposalNumber, target);
            if (!canSeeEveryProposalVersion(proposalNumber)) {
                target.denialCode = PartialFamilyAccessException.CODE;
                throw new PartialFamilyAccessException();
            }
        });
    }

    @Override
    public void requireProposal(long proposalId) {
        if (!enforced()) {
            auditUnenforcedView(RecordModule.PROPOSAL, () -> facts.proposalNumberForId(proposalId), proposalId);
            return;
        }
        AuditTarget target = new AuditTarget(RecordModule.PROPOSAL, null, proposalId);
        audited(target, () -> checkProposal(proposalId, target));
    }

    private void checkProposal(long proposalId, AuditTarget target) {
        requireProvisioned();
        String proposalNumber = facts.proposalNumberForId(proposalId).orElseThrow(RecordNotAccessibleException::new);
        if (target != null) {
            target.key = proposalNumber;
        }
        requireVersion(facts.proposalFamily(proposalNumber), String.valueOf(proposalId),
                RecordAccessEvaluator.DecisionContext.DIRECT, target);
    }

    @Override
    public void requireProposalNumber(String proposalNumber) {
        if (!enforced()) {
            auditUnenforcedView(RecordModule.PROPOSAL,
                    () -> facts.latestProposalVersionKey(proposalNumber).map(version -> proposalNumber), null);
            return;
        }
        AuditTarget target = new AuditTarget(RecordModule.PROPOSAL, proposalNumber, null);
        audited(target, () -> checkProposalNumber(proposalNumber, target));
    }

    private void checkProposalNumber(String proposalNumber, AuditTarget target) {
        requireProvisioned();
        String versionKey = facts.latestProposalVersionKey(proposalNumber).orElseThrow(RecordNotAccessibleException::new);
        requireVersion(facts.proposalFamily(proposalNumber), versionKey,
                RecordAccessEvaluator.DecisionContext.DIRECT, target);
    }

    /*
     * The canSee* family answers "should this RELATED record be listed?" for
     * a page about something else. Those are not views of the related record,
     * so they call the non-recording check* methods: a Negotiation page that
     * asks whether its Proposal is visible must not leave a row saying the
     * Proposal was opened.
     */

    /** Whether a related record should be listed; never throws, never audited. */
    @Override
    public boolean canSeeAwardNumber(String awardNumber) {
        return silently(() -> checkAwardNumber(awardNumber, null));
    }

    @Override
    public boolean canSeeProposalNumber(String proposalNumber) {
        return silently(() -> checkProposalNumber(proposalNumber, null));
    }

    @Override
    public boolean canSeeProposal(long proposalId) {
        return silently(() -> checkProposal(proposalId, null));
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
        return silently(() -> checkAward(awardId, null));
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
            auditUnenforcedView(RecordModule.AWARD,
                    () -> facts.currentAwardVersionKey(awardNumber).map(version -> awardNumber), null);
            return;
        }
        AuditTarget target = new AuditTarget(RecordModule.AWARD, awardNumber, null);
        audited(target, () -> {
            checkAwardNumber(awardNumber, target);
            if (!canSeeEveryAwardVersion(awardNumber)) {
                target.denialCode = PartialFamilyAccessException.CODE;
                throw new PartialFamilyAccessException();
            }
        });
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

    /**
     * Decides one version. Records NOTHING: recording belongs to the public
     * require* entry points, which know whether this is the record the
     * request is about (see {@link #audited}). {@code target}, when given,
     * receives the business key and the reason or denial code for that row.
     */
    private void requireVersion(List<RecordFacts> family, String versionKey,
                                RecordAccessEvaluator.DecisionContext context, AuditTarget target) {
        RecordFacts version = family.stream()
                .filter(v -> v.versionKey().equals(versionKey))
                .findFirst()
                .orElseThrow(RecordNotAccessibleException::new);
        AuthorizationPolicy policy = properties.policy().orElseThrow(IdentityAccessDeniedException::new);
        AccessDecision decision = new RecordAccessEvaluator(policy, unitHierarchy)
                .decide(outcome(), version, family, context);

        if (target != null) {
            target.key = version.familyKey();
        }
        if (!decision.allowed()) {
            if (target != null) {
                target.denialCode = decision.denialCode();
            }
            throw new RecordNotAccessibleException();
        }
        if (target != null) {
            target.reason = decision.reasons().isEmpty() ? null
                    : decision.reasons().iterator().next().name();
        }
    }

    // ── SEC-008 / US-SEC-004 activity recording ─────────────────────────

    /**
     * What one activity row is about. Filled in by the decision as it learns
     * it: the business key is often only known after a lookup by row id.
     */
    private static final class AuditTarget {
        final RecordModule module;
        final Long recordId;
        String key;
        String reason;
        String denialCode;

        AuditTarget(RecordModule module, String key, Long recordId) {
            this.module = module;
            this.key = key;
            this.recordId = recordId;
        }
    }

    private static final String RECORDED_ATTRIBUTE =
            RecordAuthorizationService.class.getName() + ".activityRecorded";

    /** The reason a view carries when record authorization is not enforced. */
    public static final String NOT_ENFORCED_REASON = "NOT_ENFORCED";

    /**
     * Runs one record decision and records its result ONCE.
     *
     * <p>The single recording point for record views and refusals, reached
     * only from the public require* entry points - the methods a service
     * calls for the record the request is ABOUT. Inherited Award checks made
     * on behalf of a Subaward or Negotiation, and canSee* questions about
     * related records, go through the non-recording check* methods and never
     * reach here: they would otherwise report a view of a record the caller
     * never opened.
     *
     * <p>A denial is recorded and the original exception rethrown unchanged;
     * {@code recordAccessDenied} cannot throw, so recording can never turn a
     * refusal into anything else. A permit is recorded after the decision and
     * may throw {@code AuditUnavailableException} under a FAIL_CLOSED
     * routine-read policy - deliberately not caught, because swallowing it
     * would reinstate FAIL_OPEN silently.
     *
     * <p>With collection off this is exactly {@code check.run()}.
     */
    private void audited(AuditTarget target, Runnable check) {
        if (!activity.enabled()) {
            check.run();
            return;
        }
        try {
            check.run();
        } catch (RuntimeException refused) {
            if (firstThisRequest("DENIED", target)) {
                activity.recordAccessDenied(target.module.name(), target.key,
                        denialCode(target, refused), target.recordId);
            }
            throw refused;
        }
        if (firstThisRequest("VIEWED", target)) {
            activity.recordViewed(target.module.name(), target.key, target.reason, target.recordId);
        }
    }

    /**
     * Enforcement OFF, collection ON: the read still happens, so it is still
     * recorded (US-SEC-004.1). Outcome PERMITTED, reason
     * {@value #NOT_ENFORCED_REASON}.
     *
     * <p>The business-key lookup runs ONLY when collection is on. A record
     * that does not exist is not recorded - the service is about to 404 it
     * and nothing was viewed. A lookup that FAILS records the view with no
     * key rather than losing it.
     */
    private void auditUnenforcedView(RecordModule module, Supplier<Optional<String>> key, Long recordId) {
        if (!activity.enabled()) {
            return;
        }
        String resolved;
        try {
            Optional<String> found = key.get();
            if (found.isEmpty()) {
                return;
            }
            resolved = found.get();
        } catch (RuntimeException lookupFailed) {
            resolved = null;
        }
        AuditTarget target = new AuditTarget(module, resolved, recordId);
        if (firstThisRequest("VIEWED", target)) {
            activity.recordViewed(module.name(), resolved, NOT_ENFORCED_REASON, recordId);
        }
    }

    /**
     * One row per record per request. A single HTTP request can ask about the
     * same record more than once (a service that checks by number and then by
     * id, say); the user still opened it once.
     */
    private boolean firstThisRequest(String kind, AuditTarget target) {
        RequestAttributes request = RequestContextHolder.getRequestAttributes();
        if (request == null) {
            return true;
        }
        @SuppressWarnings("unchecked")
        Set<String> seen = (Set<String>) request.getAttribute(RECORDED_ATTRIBUTE,
                RequestAttributes.SCOPE_REQUEST);
        if (seen == null) {
            seen = new java.util.HashSet<>();
            request.setAttribute(RECORDED_ATTRIBUTE, seen, RequestAttributes.SCOPE_REQUEST);
        }
        return seen.add(kind + '|' + target.module + '|' + target.key + '|' + target.recordId);
    }

    private String denialCode(AuditTarget target, RuntimeException refused) {
        if (target.denialCode != null) {
            return target.denialCode;
        }
        if (refused instanceof AccessNotProvisionedException
                || refused instanceof IdentityAccessDeniedException
                || refused instanceof ReauthenticationRequiredException) {
            try {
                return switch (outcome()) {
                    case AccessOutcome.NotProvisioned notProvisioned -> notProvisioned.reason().name();
                    case AccessOutcome.Denied denied -> denied.reason().name();
                    case AccessOutcome.Scoped ignored -> AccessOutcome.DenialReason.POLICY_NOT_CONFIGURED.name();
                };
            } catch (RuntimeException failure) {
                return AccessOutcome.DenialReason.EVALUATION_FAILED.name();
            }
        }
        if (refused instanceof RecordNotAccessibleException) {
            return "RECORD_NOT_FOUND";
        }
        return AccessOutcome.DenialReason.EVALUATION_FAILED.name();
    }

    private boolean centralCaller() {
        return outcome() instanceof AccessOutcome.Scoped scoped && scoped.scope().central();
    }

    /**
     * Can this caller see this Award <em>for the purpose of deciding a
     * Subaward or Negotiation</em>?
     *
     * <p>SEC-005: identical to {@link #canSeeAward} except that PARENT_AWARD
     * grants do not count. A separate method rather than a flag on the public
     * one, so no caller can reach the inherited semantics by accident, and so
     * the two uses are visible in a call hierarchy.
     */
    private boolean canSeeAwardForInheritance(long awardId) {
        return silently(() -> {
            requireProvisioned();
            String awardNumber = facts.awardNumberForId(awardId)
                    .orElseThrow(RecordNotAccessibleException::new);
            requireVersion(facts.awardFamily(awardNumber), String.valueOf(awardId),
                    RecordAccessEvaluator.DecisionContext.INHERITED, null);
        });
    }

    /** As {@link #canSeeAwardNumber}, ignoring PARENT_AWARD grants (SEC-005). */
    private boolean canSeeAwardNumberForInheritance(String awardNumber) {
        return silently(() -> {
            requireProvisioned();
            String versionKey = facts.currentAwardVersionKey(awardNumber)
                    .orElseThrow(RecordNotAccessibleException::new);
            requireVersion(facts.awardFamily(awardNumber), versionKey,
                    RecordAccessEvaluator.DecisionContext.INHERITED, null);
        });
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
                    new AccessStatus("ENFORCED", "record authorization enforced",
                            denied.reason() == AccessOutcome.DenialReason.REAUTHENTICATION_REQUIRED
                                    ? ReauthenticationRequiredException.CODE : "ACCESS_DENIED",
                            List.of());
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
