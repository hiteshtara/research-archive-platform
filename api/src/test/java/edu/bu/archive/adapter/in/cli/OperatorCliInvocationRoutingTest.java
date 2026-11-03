package edu.bu.archive.adapter.in.cli;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.DefaultApplicationArguments;

/**
 * Two operator runners share the {@code grant-admin} profile, and Spring runs
 * every {@link org.springframework.boot.ApplicationRunner} in the context. How
 * one invocation is routed to exactly one of them is therefore a safety
 * property, not a convenience.
 *
 * <p>{@link EnrollmentNonWebStartupTest} proves the same thing end to end
 * through a real context; these are the cheap boundary cases around it.
 */
@DisplayName("One operator invocation reaches exactly one runner")
class OperatorCliInvocationRoutingTest {

    private static DefaultApplicationArguments args(String... values) {
        return new DefaultApplicationArguments(values);
    }

    @Test
    void theGrantRunnerStandsDownForEitherSpellingOfTheEnrollmentVerb() {
        assertThat(GrantProvisioningCli.isEnrollment(args("--enrol-cognito-subject=x"))).isTrue();
        assertThat(GrantProvisioningCli.isEnrollment(args("--enroll-cognito-subject=x"))).isTrue();
    }

    @Test
    void theGrantRunnerStillActsOnAGrantInvocation() {
        assertThat(GrantProvisioningCli.isEnrollment(args("--grantee=ARP-ADMN0001-00000001")))
                .isFalse();
        assertThat(GrantProvisioningCli.isEnrollment(args("--revoke-grant-id=1"))).isFalse();
    }

    @Test
    void anInvocationWithNoVerbAtAllStillReachesTheGrantRunnerToBeRefusedOutLoud() {
        /*
         * Deliberate: skipping whenever --grantee is absent would turn a
         * mistyped flag into a silent exit 0. A grant invocation that names
         * nothing must be refused, not ignored.
         */
        assertThat(GrantProvisioningCli.isEnrollment(args("--dry-run"))).isFalse();
    }

    @Test
    void theEnrollmentRunnerDoesNothingWithoutItsOwnVerb() {
        IdentityEnrollmentCli cli = new IdentityEnrollmentCli(null, null, null, null);
        assertThatCode(() -> cli.run(args("--grantee=ARP-ADMN0001-00000001", "--dry-run")))
                .as("a grant invocation must not be touched by the enrollment runner,"
                        + " and the null collaborators prove nothing was reached")
                .doesNotThrowAnyException();
    }

    @Test
    void anInvocationCarryingBothVerbsIsRefusedBeforeAnythingIsWritten() {
        IdentityEnrollmentCli cli = new IdentityEnrollmentCli(null, null, null, null);
        for (String grantVerb : new String[] {
            "--grantee=ARP-ADMN0001-00000001", "--revoke-grant-id=1"}) {
            assertThatThrownBy(() -> cli.run(args(
                            "--enrol-cognito-subject=0fa15e00-0000-4000-8000-000000000fab",
                            grantVerb, "--dry-run")))
                    .as("the grant runner stands down for enrollment, so proceeding would"
                            + " enrol and silently issue no grant")
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("both an enrollment verb and a grant verb");
        }
    }
}
