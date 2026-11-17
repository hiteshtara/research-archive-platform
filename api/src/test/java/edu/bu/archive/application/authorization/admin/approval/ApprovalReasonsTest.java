package edu.bu.archive.application.authorization.admin.approval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;

/**
 * The interim copy of the reason rule (D4). When A1's shared {@code Reasons}
 * lands, this class and {@link ApprovalReasons} are deleted and the service
 * calls {@code Reasons.require} - these cases are a subset of
 * {@code docs/security/reason-rule-cases.json} on that branch, so the switch
 * cannot loosen anything.
 */
class ApprovalReasonsTest {

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {
            "", "   ", "short one", "1234567890", "----------", "aaaaaaaaaaaa",
            "test test test", "access access access", "n/a", "N / A", "TBD - TODO",
            "test, testing", "see above as discussed", "lorem ipsum", "no reason",
            "placeholder placeholder"})
    void refused(String reason) {
        assertThatThrownBy(() -> ApprovalReasons.require(reason))
                .isInstanceOf(PrivilegeApprovalException.class)
                .extracting(e -> ((PrivilegeApprovalException) e).refusal())
                .isEqualTo(PrivilegeApprovalException.Refusal.REASON_REQUIRED);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "Approved by the research office for the audit",
            "Ticket SYN-1234 requests central access",
            "test the new unit grant for the dean"})
    void accepted(String reason) {
        assertThat(ApprovalReasons.require(reason)).isEqualTo(ApprovalReasons.normalize(reason));
    }

    @Test
    void longerThanFiveHundredIsRefused() {
        assertThatThrownBy(() -> ApprovalReasons.require("word ".repeat(120) + "end of it"))
                .isInstanceOf(PrivilegeApprovalException.class);
        // Exactly 500 after normalisation is accepted.
        String fiveHundred = ("ab " .repeat(166) + "cd").substring(0, 500);
        assertThat(ApprovalReasons.require(fiveHundred).length()).isLessThanOrEqualTo(500);
    }

    @Test
    void storedNormalised() {
        assertThat(ApprovalReasons.require("  Left   the\tdepartment \n "))
                .isEqualTo("Left the department");
    }
}
