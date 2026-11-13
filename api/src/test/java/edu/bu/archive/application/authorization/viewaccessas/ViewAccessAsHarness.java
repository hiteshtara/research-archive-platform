package edu.bu.archive.application.authorization.viewaccessas;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import edu.bu.archive.application.authorization.AccessDecision;
import edu.bu.archive.application.authorization.AccessOutcome;
import edu.bu.archive.application.authorization.AccessScopeResolver;
import edu.bu.archive.application.authorization.AuthorizationPolicy;
import edu.bu.archive.application.authorization.ContactRelationships;
import edu.bu.archive.application.authorization.IdentityResolution;
import edu.bu.archive.application.authorization.RecordAccessEvaluator;
import edu.bu.archive.application.authorization.RecordFacts;
import edu.bu.archive.application.authorization.UnitHierarchy;
import edu.bu.archive.application.authorization.ValidatedCognitoIdentity;
import edu.bu.archive.application.authorization.fixtures.InMemoryAccessGrantRepository;

/**
 * "View Access As": what would the holder of THIS grant shape see?
 *
 * <p>TEST SCOPE ONLY. Nothing in this package is packaged into the deployable
 * jar - {@code edu.bu.archive.ops.ViewAccessAsAbsentFromJarTest} asserts that
 * on the built artifact, with a positive control.
 *
 * <h2>It contains no authorization rules</h2>
 *
 * Every decision comes from the real {@link AccessScopeResolver} and the real
 * {@link RecordAccessEvaluator}. There is deliberately not one {@code if}
 * statement here about units, roles, modules or descendants. A harness that
 * reimplemented any rule would agree with itself and prove nothing about
 * production.
 *
 * <h2>Why not RecordAuthorizationGate</h2>
 *
 * {@code RecordAuthorizationGate} is the production enforcement switch, and
 * with enforcement off it returns {@code allow(Set.of())} unconditionally. DEV
 * has enforcement off and must keep it off, so a harness built on the gate
 * would report PERMIT for everything and look like it worked. Constructing the
 * evaluator directly with an explicit policy is what "enforcement enabled only
 * inside isolated evaluation" means in practice: the policy lives in this
 * object, and shared {@code AuthorizationProperties} is never read or written.
 *
 * <h2>State</h2>
 *
 * Each instance owns its own grant repository, so two harnesses cannot see
 * each other's grants and a profile cannot leak between tests.
 */
public final class ViewAccessAsHarness {

    private final AuthorizationPolicy policy;
    private final UnitHierarchy unitHierarchy;
    private final Instant evaluatedAt;

    public ViewAccessAsHarness(AuthorizationPolicy policy, UnitHierarchy unitHierarchy, Instant evaluatedAt) {
        this.policy = policy;
        this.unitHierarchy = unitHierarchy;
        this.evaluatedAt = evaluatedAt;
    }

    /**
     * Resolve a profile's grants into a scope, exactly as a real request does.
     *
     * <p>The {@link IdentityResolution.Mapped} is built as a value object from
     * a synthetic issuer and subject. No token is parsed, no signature is
     * checked, nothing passes through {@code SecurityConfiguration}, and the
     * subject is not a real pool subject - so this cannot be, and is not, a
     * substituted JWT.
     */
    public AccessOutcome outcomeFor(SyntheticProfile profile) {
        var grants = new InMemoryAccessGrantRepository();
        profile.grants().forEach(grants::add);

        var resolution = new IdentityResolution.Mapped(
                new ValidatedCognitoIdentity(
                        "simulation://view-access-as/not-a-user-pool",
                        "simulated-" + profile.name()),
                SyntheticProfile.GRANTEE,
                profile.kualiPersonId());

        // The real resolver, the real clock semantics: revoked, expired and
        // not-yet-valid grants are filtered by AccessGrant.isActiveAt.
        return new AccessScopeResolver(
                grants,
                Clock.fixed(evaluatedAt, ZoneOffset.UTC),
                () -> Optional.of(policy),
                ContactRelationships.NONE)
                .resolve(resolution);
    }

    /**
     * One record, one profile.
     *
     * @param familyVersions every version of the record's family, consulted
     *                       only under {@code FAMILY_WIDE}
     */
    public Evaluation evaluate(SyntheticProfile profile, RecordFacts record, List<RecordFacts> familyVersions) {
        AccessOutcome outcome = outcomeFor(profile);
        AccessDecision decision =
                new RecordAccessEvaluator(policy, unitHierarchy).decide(outcome, record, familyVersions);
        return new Evaluation(outcome, decision, Verdict.of(decision), Verdict.rule(decision));
    }

    /** A record evaluated on its own, with no family context. */
    public Evaluation evaluate(SyntheticProfile profile, RecordFacts record) {
        return evaluate(profile, record, List.of(record));
    }

    public AuthorizationPolicy policy() {
        return policy;
    }

    public Instant evaluatedAt() {
        return evaluatedAt;
    }

    /** What the engine said, and the reader's three-valued view of it. */
    public record Evaluation(
            AccessOutcome outcome,
            AccessDecision decision,
            Verdict verdict,
            String rule
    ) { }
}
