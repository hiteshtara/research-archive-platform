package edu.bu.archive.application.authorization.admin.provisioning;

import java.util.List;

/** What a provisioning attempt decided, and why. */
public record GrantOutcome(
        Result result, List<String> checks, List<String> problems, String message, Long grantId) {

    public enum Result { DRY_RUN_OK, APPLIED, REFUSED }

    public boolean wrote() {
        return result == Result.APPLIED;
    }
}
