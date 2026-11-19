package edu.bu.archive.application.authorization.viewaccessas;

import java.util.Set;

import edu.bu.archive.application.authorization.AccessDecision;
import edu.bu.archive.application.authorization.AccessOutcome;
import edu.bu.archive.application.authorization.AccessReason;

/**
 * A three-valued reading of the engine's two-valued {@link AccessDecision}.
 *
 * <p>{@code AccessDecision} is live authorization code and is deliberately NOT
 * changed to carry a third value: production treats every non-allow as a
 * denial, and widening the type would change real behaviour. The third value
 * exists only here, for the reader.
 *
 * <p>The distinction that matters: {@link #DENY} means the engine decided, and
 * {@link #INDETERMINATE} means it could not. A tester who reads
 * {@code POLICY_NOT_CONFIGURED} as "correctly denied" has concluded that
 * authorization works when in fact nothing was evaluated.
 */
public enum Verdict {

    PERMIT,
    DENY,
    INDETERMINATE;

    /** Denial codes that mean no decision was reached. */
    private static final Set<String> NOT_DECIDED = Set.of(
            AccessOutcome.DenialReason.POLICY_NOT_CONFIGURED.name(),
            AccessOutcome.DenialReason.EVALUATION_FAILED.name());

    public static Verdict of(AccessDecision decision) {
        if (decision.allowed()) {
            return PERMIT;
        }
        return NOT_DECIDED.contains(decision.denialCode()) ? INDETERMINATE : DENY;
    }

    /**
     * Why, in the engine's own terms. Derived from the decision, never
     * recomputed: a harness that worked out the rule for itself would be a
     * second authorization implementation and would prove nothing about the
     * first.
     */
    public static String rule(AccessDecision decision) {
        if (decision.allowed()) {
            Set<AccessReason> reasons = decision.reasons();
            return reasons.isEmpty() ? "allowed with no reason (enforcement off)" : describe(reasons);
        }
        return decision.denialCode();
    }

    private static String describe(Set<AccessReason> reasons) {
        StringBuilder text = new StringBuilder();
        for (AccessReason reason : reasons) {
            if (!text.isEmpty()) {
                text.append('+');
            }
            text.append(reason.name());
        }
        return text.toString();
    }
}
