package edu.bu.archive.application.authorization;

import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * The single switch between today's behaviour and enforcement.
 *
 * <ul>
 *   <li>Enforcement OFF: every record is allowed, exactly as today, and the
 *       status is labelled {@value #NOT_ENFORCED_LABEL}.</li>
 *   <li>Enforcement ON: a missing policy, a missing identity or any failure
 *       while resolving or evaluating DENIES - never unrestricted access.</li>
 * </ul>
 * Not yet called from live request handling.
 */
public class RecordAuthorizationGate {

    public static final String NOT_ENFORCED_LABEL = "record authorization not enforced";

    public enum Status { NOT_ENFORCED, ENFORCED, ENFORCED_POLICY_NOT_CONFIGURED }

    private final AuthorizationProperties properties;
    private final UnitHierarchy unitHierarchy;

    public RecordAuthorizationGate(AuthorizationProperties properties, UnitHierarchy unitHierarchy) {
        this.properties = Objects.requireNonNull(properties);
        this.unitHierarchy = Objects.requireNonNull(unitHierarchy);
    }

    public Status status() {
        if (!properties.isEnforcementEnabled()) {
            return Status.NOT_ENFORCED;
        }
        return properties.policy().isPresent() ? Status.ENFORCED : Status.ENFORCED_POLICY_NOT_CONFIGURED;
    }

    public AccessDecision decide(
            Supplier<AccessOutcome> outcome,
            RecordFacts record,
            List<RecordFacts> familyVersions
    ) {
        if (!properties.isEnforcementEnabled()) {
            return AccessDecision.allow(java.util.Set.of());
        }
        var policy = properties.policy();
        if (policy.isEmpty()) {
            return AccessDecision.deny(AccessOutcome.DenialReason.POLICY_NOT_CONFIGURED.name());
        }
        try {
            AccessOutcome resolved = outcome == null ? null : outcome.get();
            if (resolved == null) {
                return AccessDecision.deny("ACCESS_NOT_PROVISIONED");
            }
            return new RecordAccessEvaluator(policy.get(), unitHierarchy).decide(resolved, record, familyVersions);
        } catch (RuntimeException failure) {
            return AccessDecision.deny(AccessOutcome.DenialReason.EVALUATION_FAILED.name());
        }
    }
}
