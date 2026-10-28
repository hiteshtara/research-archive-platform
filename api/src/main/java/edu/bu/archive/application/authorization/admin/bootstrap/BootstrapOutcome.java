package edu.bu.archive.application.authorization.admin.bootstrap;

import java.util.List;

/**
 * What the utility did, and what it checked to decide.
 *
 * <p>{@code checks} is returned rather than logged so the caller controls
 * where it goes. It carries no token, no password and no secret; identifiers
 * appear masked.
 */
public record BootstrapOutcome(
        Result result,
        List<String> checks,
        List<String> problems,
        String message,
        Long adminMemberId) {

    public enum Result {
        /** Every check passed and the membership was written. */
        APPLIED,
        /** Every check passed; nothing was written because this was a dry run. */
        DRY_RUN_OK,
        /** At least one check failed. Nothing was written. */
        REFUSED
    }

    public boolean wrote() {
        return result == Result.APPLIED;
    }
}
