package edu.bu.archive.application.authorization.admin.provisioning;

import java.util.List;

/** What an enrollment attempt decided, and why. */
public record EnrollmentOutcome(
        Result result, List<String> checks, List<String> problems, String message,
        Long identityLinkId, String mintedIdentifier) {

    public enum Result { DRY_RUN_OK, APPLIED, REFUSED }

    public boolean wrote() {
        return result == Result.APPLIED;
    }
}
