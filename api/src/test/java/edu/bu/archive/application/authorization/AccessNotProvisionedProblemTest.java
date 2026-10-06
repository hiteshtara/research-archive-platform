package edu.bu.archive.application.authorization;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** The "access not provisioned" outcome; not wired into live handling yet. */
class AccessNotProvisionedProblemTest {

    @Test
    void tellsTheUserWhatHappenedWithoutDisclosingAnyRecord() {
        var body = AccessNotProvisionedProblem.body("/api/v1/awards/search");
        assertThat(body).containsEntry("status", 403)
                .containsEntry("code", "ACCESS_NOT_PROVISIONED")
                .containsEntry("path", "/api/v1/awards/search");
        assertThat((String) body.get("message"))
                .contains("signed in")
                .contains("not been set up")
                .doesNotContainIgnoringCase("award ");
        assertThat(body).containsOnlyKeys("status", "code", "message", "path");
    }
}
