package edu.bu.archive.application.authorization.admin.provisioning;

import java.util.List;

/**
 * The result of an identity-link attempt: what passed, what refused it, and
 * the link id when one was written.
 *
 * <p>Deliberately the same shape as {@link GrantOutcome} rather than a reuse
 * of it. The two carry different ids and are printed by different utilities,
 * and a shared record would invite a method that takes "an outcome" and has
 * to ask which kind it is.
 */
public record IdentityLinkOutcome(
        Result result, List<String> checks, List<String> problems, String message, Long linkId) {

    public enum Result { DRY_RUN_OK, APPLIED, REFUSED }

    public boolean wrote() {
        return result == Result.APPLIED;
    }
}
