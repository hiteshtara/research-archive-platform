package edu.bu.archive.adapter.out.persistence.authorization;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import edu.bu.archive.application.authorization.GrantType;
import edu.bu.archive.application.authorization.InstitutionalIdentifier;
import edu.bu.archive.application.authorization.KualiPersonId;
import edu.bu.archive.application.authorization.admin.approval.ApprovalBinding;
import edu.bu.archive.application.authorization.admin.approval.PrivilegeAction;
import edu.bu.archive.application.authorization.admin.approval.PrivilegeApproval;
import edu.bu.archive.application.authorization.admin.approval.PrivilegeApprovalException;
import edu.bu.archive.application.authorization.admin.approval.PrivilegeApprovalException.Refusal;
import edu.bu.archive.application.authorization.admin.approval.PrivilegeApprovalService;

/**
 * The approval model (A-category design, sections 3.3 and 7.3) against a real
 * PostgreSQL with the held-back V090 applied. Synthetic identities only.
 *
 * <p>Every refusal is asserted to leave the approval in the state it was in
 * and to write ZERO audit rows; every successful transition exactly one.
 */
@Tag("database")
@Testcontainers(disabledWithoutDocker = true)
class PrivilegeApprovalWorkflowIntegrationTest {

    private static final InstitutionalIdentifier ADMIN_A = new InstitutionalIdentifier("SYN-ADMIN-A");
    private static final InstitutionalIdentifier ADMIN_B = new InstitutionalIdentifier("SYN-ADMIN-B");
    private static final InstitutionalIdentifier ADMIN_C = new InstitutionalIdentifier("SYN-ADMIN-C");
    private static final InstitutionalIdentifier NOT_ADMIN = new InstitutionalIdentifier("SYN-USER-X");
    private static final InstitutionalIdentifier USER_Y = new InstitutionalIdentifier("SYN-USER-Y");

    private static final String REASON = "synthetic request for the approval workflow test";
    private static final String APPROVAL_REASON = "checked against the synthetic ticket record";
    private static final Instant EXPIRY = Instant.parse("2030-01-01T00:00:00.123456Z");
    private static final String ISSUER = "https://cognito-idp.us-east-1.amazonaws.com/us-east-1_SYNTHETIC";
    private static final String SUB_1 = "11111111-1111-1111-1111-111111111111";
    private static final String SUB_2 = "22222222-2222-2222-2222-222222222222";

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg17").asCompatibleSubstituteFor("postgres"));

    private static PrivilegeApprovalTestDatabase db;
    private static PrivilegeApprovalService service;

    /** Grants created per test, referenced by SELF_WIDEN cases. */
    private static long grantOne;
    private static long grantTwo;

    @BeforeAll
    static void setUp() throws Exception {
        db = PrivilegeApprovalTestDatabase.create(postgres);
        service = db.approvalService;
    }

    @BeforeEach
    void reset() {
        db.reset();
        db.seedAdministrator(ADMIN_A.value());
        db.seedAdministrator(ADMIN_B.value());
        db.seedAdministrator(ADMIN_C.value());
        grantOne = db.seedGrant(ADMIN_A.value(), "SYN-U-100");
        grantTwo = db.seedGrant(ADMIN_A.value(), "SYN-U-200");
    }

    // ── helpers ─────────────────────────────────────────────────────────

    private static ApprovalBinding unitGrant() {
        return new ApprovalBinding(GrantType.UNIT, "SYN-U-100", null, null, false, EXPIRY,
                null, null, null);
    }

    private static ApprovalBinding central() {
        return new ApprovalBinding(GrantType.CENTRAL, null, null, null, false, null,
                null, null, null);
    }

    private PrivilegeApproval requested(PrivilegeAction action, InstitutionalIdentifier requester,
                                        InstitutionalIdentifier subject, ApprovalBinding binding) {
        return service.requestApproval(requester, action, subject, binding, REASON);
    }

    private PrivilegeApproval approved(PrivilegeAction action, InstitutionalIdentifier requester,
                                       InstitutionalIdentifier subject, ApprovalBinding binding,
                                       InstitutionalIdentifier approver) {
        PrivilegeApproval request = requested(action, requester, subject, binding);
        return service.approve(approver, request.approvalId(), APPROVAL_REASON);
    }

    /** The protected write, as phase 2 will call it: inside one transaction. */
    private PrivilegeApproval execute(PrivilegeAction action, InstitutionalIdentifier subject,
                                      ApprovalBinding binding, InstitutionalIdentifier executor) {
        return new TransactionTemplate(db.tx).execute(s ->
                service.requireConsumedApproval(action, subject, binding, executor));
    }

    private static void assertRefused(Runnable call, Refusal refusal) {
        assertThatThrownBy(call::run)
                .isInstanceOf(PrivilegeApprovalException.class)
                .extracting(e -> ((PrivilegeApprovalException) e).refusal())
                .isEqualTo(refusal);
    }

    // ── ordering ────────────────────────────────────────────────────────

    @Test
    @DisplayName("the full path: request, approve, execute - one audit row per transition")
    void requestApproveExecuteWritesOneAuditRowPerTransition() {
        PrivilegeApproval request = requested(PrivilegeAction.CENTRAL_GRANT, ADMIN_A, USER_Y, central());
        assertThat(request.status()).isEqualTo(PrivilegeApproval.Status.PENDING);
        assertThat(request.validUntil())
                .as("72 hours from the request (D2)")
                .isEqualTo(request.requestedAt().plus(72, ChronoUnit.HOURS));
        assertThat(db.auditRows("PRIVILEGE_APPROVAL_REQUESTED")).isEqualTo(1);

        PrivilegeApproval approved = service.approve(ADMIN_B, request.approvalId(), APPROVAL_REASON);
        assertThat(approved.status()).isEqualTo(PrivilegeApproval.Status.APPROVED);
        assertThat(approved.approvedBy()).isEqualTo(ADMIN_B.value());
        assertThat(db.auditRows("PRIVILEGE_APPROVAL_GRANTED")).isEqualTo(1);

        PrivilegeApproval consumed = execute(PrivilegeAction.CENTRAL_GRANT, USER_Y, central(), ADMIN_A);
        assertThat(consumed.status()).isEqualTo(PrivilegeApproval.Status.CONSUMED);
        assertThat(consumed.consumedBy()).isEqualTo(ADMIN_A.value());
        assertThat(db.auditRows("PRIVILEGE_APPROVAL_CONSUMED")).isEqualTo(1);
        assertThat(db.auditRows()).isEqualTo(3);
    }

    @Test
    @DisplayName("execute with no approval at all is refused, with no audit row")
    void executeWithoutApprovalIsRefused() {
        assertRefused(() -> execute(PrivilegeAction.CENTRAL_GRANT, USER_Y, central(), ADMIN_A),
                Refusal.NO_APPROVAL);
        assertThat(db.auditRows()).isZero();
    }

    @Test
    @DisplayName("execute with a PENDING approval is refused and the approval stays PENDING")
    void executeWithPendingApprovalIsRefused() {
        PrivilegeApproval request = requested(PrivilegeAction.CENTRAL_GRANT, ADMIN_A, USER_Y, central());
        int before = db.auditRows();

        assertRefused(() -> execute(PrivilegeAction.CENTRAL_GRANT, USER_Y, central(), ADMIN_A),
                Refusal.NOT_APPROVED);
        assertThat(db.status(request.approvalId())).isEqualTo("PENDING");
        assertThat(db.auditRows()).isEqualTo(before);
    }

    @Test
    @DisplayName("requireConsumedApproval refuses to run outside the caller's transaction")
    void consumeRequiresTheCallersTransaction() {
        approved(PrivilegeAction.CENTRAL_GRANT, ADMIN_A, USER_Y, central(), ADMIN_B);
        assertThatThrownBy(() ->
                service.requireConsumedApproval(PrivilegeAction.CENTRAL_GRANT, USER_Y, central(), ADMIN_A))
                .isInstanceOf(IllegalTransactionStateException.class);
    }

    // ── who may approve ─────────────────────────────────────────────────

    @Test
    @DisplayName("the requester cannot approve their own request (service)")
    void requesterCannotApprove() {
        PrivilegeApproval request = requested(PrivilegeAction.CENTRAL_GRANT, ADMIN_A, USER_Y, central());
        int before = db.auditRows();

        assertRefused(() -> service.approve(ADMIN_A, request.approvalId(), APPROVAL_REASON),
                Refusal.REQUESTER_CANNOT_APPROVE);
        assertThat(db.status(request.approvalId())).isEqualTo("PENDING");
        assertThat(db.auditRows()).isEqualTo(before);
    }

    @Test
    @DisplayName("the beneficiary cannot approve their own escalation (service)")
    void beneficiaryCannotApprove() {
        PrivilegeApproval request = requested(PrivilegeAction.CENTRAL_GRANT, ADMIN_A, ADMIN_B, central());
        int before = db.auditRows();

        assertRefused(() -> service.approve(ADMIN_B, request.approvalId(), APPROVAL_REASON),
                Refusal.BENEFICIARY_CANNOT_APPROVE);
        assertThat(db.status(request.approvalId())).isEqualTo("PENDING");
        assertThat(db.auditRows()).isEqualTo(before);
    }

    @Test
    @DisplayName("a non-administrator, or a suspended administrator, cannot approve")
    void ineligibleApproversAreRefused() {
        PrivilegeApproval request = requested(PrivilegeAction.CENTRAL_GRANT, ADMIN_A, USER_Y, central());
        int before = db.auditRows();

        assertRefused(() -> service.approve(NOT_ADMIN, request.approvalId(), APPROVAL_REASON),
                Refusal.APPROVER_INELIGIBLE);
        db.suspend(ADMIN_B.value());
        assertRefused(() -> service.approve(ADMIN_B, request.approvalId(), APPROVAL_REASON),
                Refusal.APPROVER_INELIGIBLE);
        assertThat(db.status(request.approvalId())).isEqualTo("PENDING");
        assertThat(db.auditRows()).isEqualTo(before);
    }

    @Test
    @DisplayName("QA M2: an approver cannot approve linking their OWN Cognito identity (service)")
    void anApproverCannotApproveLinkingTheirOwnCognitoIdentity() {
        // ADMIN_B signs in as SUB_1. A request to link SUB_1 to USER_Y would
        // put ADMIN_B on both sides: approving it hands their own sign-in
        // whatever USER_Y holds. V086's approveRelink refuses this; so must this.
        db.jdbc.sql("""
                INSERT INTO authz.identity_link
                    (cognito_issuer, cognito_subject, institutional_identifier, method, status,
                     verified_by, verified_at)
                VALUES (:issuer, :sub, :id, 'ADMIN_VERIFIED', 'ACTIVE', 'test-fixture', now())
                """).param("issuer", ISSUER).param("sub", SUB_1).param("id", ADMIN_B.value()).update();
        PrivilegeApproval request = requested(PrivilegeAction.LINK_PRIVILEGED, ADMIN_A, USER_Y,
                ApprovalBinding.forLink(ISSUER, SUB_1));
        int before = db.auditRows();

        assertRefused(() -> service.approve(ADMIN_B, request.approvalId(), APPROVAL_REASON),
                Refusal.APPROVER_IS_LINK_TARGET);
        assertThat(db.status(request.approvalId())).isEqualTo("PENDING");
        assertThat(db.auditRows()).isEqualTo(before);

        // A third administrator with no stake in that sign-in may approve it.
        assertThat(service.approve(ADMIN_C, request.approvalId(), APPROVAL_REASON).status())
                .isEqualTo(PrivilegeApproval.Status.APPROVED);
    }

    @Test
    @DisplayName("a non-administrator cannot request an approval")
    void nonAdministratorCannotRequest() {
        assertRefused(() -> requested(PrivilegeAction.CENTRAL_GRANT, NOT_ADMIN, USER_Y, central()),
                Refusal.NOT_ADMINISTRATOR);
        assertThat(db.jdbc.sql("SELECT count(*) FROM authz.privilege_approval")
                .query(Integer.class).single()).isZero();
        assertThat(db.auditRows()).isZero();
    }

    @Test
    @DisplayName("a placeholder or short reason is refused for request, approval and revocation")
    void reasonsAreValidated() {
        assertRefused(() -> service.requestApproval(ADMIN_A, PrivilegeAction.CENTRAL_GRANT, USER_Y,
                central(), "n/a"), Refusal.REASON_REQUIRED);
        assertRefused(() -> service.requestApproval(ADMIN_A, PrivilegeAction.CENTRAL_GRANT, USER_Y,
                central(), "short one"), Refusal.REASON_REQUIRED);
        PrivilegeApproval request = requested(PrivilegeAction.CENTRAL_GRANT, ADMIN_A, USER_Y, central());
        int before = db.auditRows();
        assertRefused(() -> service.approve(ADMIN_B, request.approvalId(), "tbd todo"),
                Refusal.REASON_REQUIRED);
        assertRefused(() -> service.revoke(ADMIN_B, request.approvalId(), "aaaaaaaaaaaa"),
                Refusal.REASON_REQUIRED);
        assertThat(db.status(request.approvalId())).isEqualTo("PENDING");
        assertThat(db.auditRows()).isEqualTo(before);
    }

    // ── revocation ──────────────────────────────────────────────────────

    @Test
    @DisplayName("approve after revoke is refused")
    void approveAfterRevokeIsRefused() {
        PrivilegeApproval request = requested(PrivilegeAction.CENTRAL_GRANT, ADMIN_A, USER_Y, central());
        service.revoke(ADMIN_A, request.approvalId(), "no longer needed after review");
        assertThat(db.auditRows("PRIVILEGE_APPROVAL_REVOKED")).isEqualTo(1);
        int before = db.auditRows();

        assertRefused(() -> service.approve(ADMIN_B, request.approvalId(), APPROVAL_REASON),
                Refusal.NOT_PENDING);
        assertThat(db.status(request.approvalId())).isEqualTo("REVOKED");
        assertThat(db.auditRows()).isEqualTo(before);
    }

    @Test
    @DisplayName("execute after revoke is refused")
    void executeAfterRevokeIsRefused() {
        PrivilegeApproval approval = approved(PrivilegeAction.CENTRAL_GRANT, ADMIN_A, USER_Y, central(), ADMIN_B);
        service.revoke(ADMIN_C, approval.approvalId(), "withdrawn by a third administrator");
        int before = db.auditRows();

        assertRefused(() -> execute(PrivilegeAction.CENTRAL_GRANT, USER_Y, central(), ADMIN_A),
                Refusal.NO_APPROVAL);
        assertThat(db.status(approval.approvalId())).isEqualTo("REVOKED");
        assertThat(db.auditRows()).isEqualTo(before);
    }

    @Test
    @DisplayName("revocation: a non-party non-administrator is refused; a used approval cannot be revoked")
    void revocationRules() {
        PrivilegeApproval approval = approved(PrivilegeAction.CENTRAL_GRANT, ADMIN_A, USER_Y, central(), ADMIN_B);
        int before = db.auditRows();
        assertRefused(() -> service.revoke(NOT_ADMIN, approval.approvalId(), "attempted by an outsider"),
                Refusal.NOT_PERMITTED);
        assertThat(db.auditRows()).isEqualTo(before);

        execute(PrivilegeAction.CENTRAL_GRANT, USER_Y, central(), ADMIN_A);
        int afterConsume = db.auditRows();
        assertRefused(() -> service.revoke(ADMIN_A, approval.approvalId(), "too late to withdraw this"),
                Refusal.NOT_LIVE);
        assertThat(db.status(approval.approvalId())).isEqualTo("CONSUMED");
        assertThat(db.auditRows()).isEqualTo(afterConsume);
    }

    // ── exact binding ───────────────────────────────────────────────────

    /**
     * Each case: what was approved, and an execution that differs from it in
     * exactly ONE bound field. All must be refused and leave the approval
     * APPROVED (design 7.3, "parameterised over all bound fields").
     */
    static Stream<Arguments> singleFieldMismatches() {
        Supplier<ApprovalBinding> grant = PrivilegeApprovalWorkflowIntegrationTest::unitGrant;
        return Stream.of(
                Arguments.of("action", PrivilegeAction.SELF_GRANT, grant,
                        PrivilegeAction.CENTRAL_GRANT, grant),
                Arguments.of("grant_type", PrivilegeAction.SELF_GRANT, grant, PrivilegeAction.SELF_GRANT,
                        (Supplier<ApprovalBinding>) () -> new ApprovalBinding(GrantType.IO, "SYN-U-100",
                                null, null, false, EXPIRY, null, null, null)),
                Arguments.of("unit_number", PrivilegeAction.SELF_GRANT, grant, PrivilegeAction.SELF_GRANT,
                        (Supplier<ApprovalBinding>) () -> new ApprovalBinding(GrantType.UNIT, "SYN-U-999",
                                null, null, false, EXPIRY, null, null, null)),
                Arguments.of("io_value", PrivilegeAction.SELF_GRANT, grant, PrivilegeAction.SELF_GRANT,
                        (Supplier<ApprovalBinding>) () -> new ApprovalBinding(GrantType.UNIT, "SYN-U-100",
                                "SYN-IO-1", null, false, EXPIRY, null, null, null)),
                Arguments.of("award_number", PrivilegeAction.SELF_GRANT, grant, PrivilegeAction.SELF_GRANT,
                        (Supplier<ApprovalBinding>) () -> new ApprovalBinding(GrantType.UNIT, "SYN-U-100",
                                null, "SYN-AWD-1", false, EXPIRY, null, null, null)),
                Arguments.of("include_descendants", PrivilegeAction.SELF_GRANT, grant,
                        PrivilegeAction.SELF_GRANT,
                        (Supplier<ApprovalBinding>) () -> new ApprovalBinding(GrantType.UNIT, "SYN-U-100",
                                null, null, true, EXPIRY, null, null, null)),
                Arguments.of("expires_at (later)", PrivilegeAction.SELF_GRANT, grant,
                        PrivilegeAction.SELF_GRANT,
                        (Supplier<ApprovalBinding>) () -> new ApprovalBinding(GrantType.UNIT, "SYN-U-100",
                                null, null, false, EXPIRY.plusSeconds(86_400), null, null, null)),
                Arguments.of("expires_at (removed)", PrivilegeAction.SELF_GRANT, grant,
                        PrivilegeAction.SELF_GRANT,
                        (Supplier<ApprovalBinding>) () -> new ApprovalBinding(GrantType.UNIT, "SYN-U-100",
                                null, null, false, null, null, null, null)),
                Arguments.of("target_grant_id", PrivilegeAction.SELF_WIDEN,
                        (Supplier<ApprovalBinding>) () -> ApprovalBinding.forWidening(grantOne, true, null),
                        PrivilegeAction.SELF_WIDEN,
                        (Supplier<ApprovalBinding>) () -> ApprovalBinding.forWidening(grantTwo, true, null)),
                Arguments.of("cognito_issuer", PrivilegeAction.LINK_PRIVILEGED,
                        (Supplier<ApprovalBinding>) () -> ApprovalBinding.forLink(ISSUER, SUB_1),
                        PrivilegeAction.LINK_PRIVILEGED,
                        (Supplier<ApprovalBinding>) () -> ApprovalBinding.forLink(ISSUER + "-OTHER", SUB_1)),
                Arguments.of("cognito_subject", PrivilegeAction.LINK_PRIVILEGED,
                        (Supplier<ApprovalBinding>) () -> ApprovalBinding.forLink(ISSUER, SUB_1),
                        PrivilegeAction.LINK_PRIVILEGED,
                        (Supplier<ApprovalBinding>) () -> ApprovalBinding.forLink(ISSUER, SUB_2)),
                Arguments.of("proposed_kuali_person_id", PrivilegeAction.LINK_PRIVILEGED,
                        (Supplier<ApprovalBinding>) () -> ApprovalBinding.forLink(ISSUER, SUB_1,
                                new KualiPersonId("SYN-KP-0001")),
                        PrivilegeAction.LINK_PRIVILEGED,
                        (Supplier<ApprovalBinding>) () -> ApprovalBinding.forLink(ISSUER, SUB_1,
                                new KualiPersonId("SYN-KP-0009"))));
    }

    @ParameterizedTest(name = "mismatch in {0} is refused")
    @MethodSource("singleFieldMismatches")
    void executionDifferingInOneBoundFieldIsRefused(
            String field, PrivilegeAction approvedAction, Supplier<ApprovalBinding> approvedBinding,
            PrivilegeAction executedAction, Supplier<ApprovalBinding> executedBinding) {

        // SELF_* are requested by the subject themselves; the rest by A for A here.
        PrivilegeApproval approval = approved(approvedAction, ADMIN_A, ADMIN_A,
                approvedBinding.get(), ADMIN_B);
        int before = db.auditRows();

        assertThatThrownBy(() -> execute(executedAction, ADMIN_A, executedBinding.get(), ADMIN_A))
                .as("execution differing in %s", field)
                .isInstanceOf(PrivilegeApprovalException.class)
                .extracting(e -> ((PrivilegeApprovalException) e).refusal())
                .isIn(Refusal.NO_APPROVAL, Refusal.BINDING_MISMATCH);
        assertThat(db.status(approval.approvalId())).isEqualTo("APPROVED");
        assertThat(db.auditRows()).isEqualTo(before);

        // And the approval still works for exactly what was approved.
        assertThat(execute(approvedAction, ADMIN_A, approvedBinding.get(), ADMIN_A).status())
                .isEqualTo(PrivilegeApproval.Status.CONSUMED);
    }

    // ── the Kuali principal on an identity link (owner requirement) ────

    @Test
    @DisplayName("a LINK_PRIVILEGED approval cannot be consumed with a different Kuali person ID")
    void anApprovalCannotBeConsumedWithADifferentKualiPersonId() {
        ApprovalBinding approvedLink = ApprovalBinding.forLink(ISSUER, SUB_1, new KualiPersonId("SYN-KP-0001"));
        ApprovalBinding otherPrincipal = ApprovalBinding.forLink(ISSUER, SUB_1, new KualiPersonId("SYN-KP-0002"));
        PrivilegeApproval approval = approved(PrivilegeAction.LINK_PRIVILEGED, ADMIN_A, USER_Y,
                approvedLink, ADMIN_B);
        assertThat(approval.binding().proposedKualiPersonId()).isEqualTo("SYN-KP-0001");
        int before = db.auditRows();

        assertRefused(() -> execute(PrivilegeAction.LINK_PRIVILEGED, USER_Y, otherPrincipal, ADMIN_A),
                Refusal.NO_APPROVAL);
        Boolean consumed = new TransactionTemplate(db.tx).execute(s -> db.approvals.consume(
                approval.approvalId(), PrivilegeAction.LINK_PRIVILEGED, USER_Y, otherPrincipal,
                ADMIN_A.value()));
        assertThat(consumed).as("the consume UPDATE compares the principal itself").isFalse();
        assertThat(db.status(approval.approvalId())).isEqualTo("APPROVED");
        assertThat(db.auditRows()).isEqualTo(before);

        assertThat(execute(PrivilegeAction.LINK_PRIVILEGED, USER_Y, approvedLink, ADMIN_A).status())
                .as("the approved principal still works, once")
                .isEqualTo(PrivilegeApproval.Status.CONSUMED);
    }

    @Test
    @DisplayName("null <-> value: adding or removing the principal invalidates the approval")
    void nullToValuePrincipalChangeIsRefused() {
        ApprovalBinding noPrincipal = ApprovalBinding.forLink(ISSUER, SUB_1);
        ApprovalBinding withPrincipal = ApprovalBinding.forLink(ISSUER, SUB_1, new KualiPersonId("SYN-KP-0001"));

        PrivilegeApproval none = approved(PrivilegeAction.LINK_PRIVILEGED, ADMIN_A, USER_Y, noPrincipal, ADMIN_B);
        assertRefused(() -> execute(PrivilegeAction.LINK_PRIVILEGED, USER_Y, withPrincipal, ADMIN_A),
                Refusal.NO_APPROVAL);
        assertThat(db.status(none.approvalId())).isEqualTo("APPROVED");
        Boolean nullToValue = new TransactionTemplate(db.tx).execute(s -> db.approvals.consume(
                none.approvalId(), PrivilegeAction.LINK_PRIVILEGED, USER_Y, withPrincipal, ADMIN_A.value()));
        assertThat(nullToValue).isFalse();

        InstitutionalIdentifier other = new InstitutionalIdentifier("SYN-USER-W");
        PrivilegeApproval valued = approved(PrivilegeAction.LINK_PRIVILEGED, ADMIN_A, other, withPrincipal, ADMIN_B);
        assertRefused(() -> execute(PrivilegeAction.LINK_PRIVILEGED, other, noPrincipal, ADMIN_A),
                Refusal.NO_APPROVAL);
        Boolean valueToNull = new TransactionTemplate(db.tx).execute(s -> db.approvals.consume(
                valued.approvalId(), PrivilegeAction.LINK_PRIVILEGED, other, noPrincipal, ADMIN_A.value()));
        assertThat(valueToNull).isFalse();
        assertThat(db.status(valued.approvalId())).isEqualTo("APPROVED");
        assertThat(db.auditRows("PRIVILEGE_APPROVAL_CONSUMED")).isZero();
    }

    @Test
    @DisplayName("the consume SQL itself refuses a non-matching binding, bypassing the service")
    void consumeStatementComparesEveryBoundField() {
        PrivilegeApproval approval = approved(PrivilegeAction.SELF_GRANT, ADMIN_A, ADMIN_A, unitGrant(), ADMIN_B);
        ApprovalBinding wider = new ApprovalBinding(GrantType.UNIT, "SYN-U-100", null, null, true,
                EXPIRY, null, null, null);
        Boolean consumed = new TransactionTemplate(db.tx).execute(s -> db.approvals.consume(
                approval.approvalId(), PrivilegeAction.SELF_GRANT, ADMIN_A, wider, ADMIN_A.value()));
        assertThat(consumed).isFalse();
        assertThat(db.status(approval.approvalId())).isEqualTo("APPROVED");
    }

    @Test
    @DisplayName("an approval for subject A cannot be used for subject B")
    void approvalForOneSubjectCannotBeUsedForAnother() {
        PrivilegeApproval approval = approved(PrivilegeAction.ADMIN_ASSIGN, ADMIN_A,
                new InstitutionalIdentifier("SYN-CANDIDATE-1"), ApprovalBinding.none(), ADMIN_B);
        int before = db.auditRows();

        assertRefused(() -> execute(PrivilegeAction.ADMIN_ASSIGN,
                new InstitutionalIdentifier("SYN-CANDIDATE-2"), ApprovalBinding.none(), ADMIN_A),
                Refusal.NO_APPROVAL);
        assertThat(db.status(approval.approvalId())).isEqualTo("APPROVED");
        assertThat(db.auditRows()).isEqualTo(before);
    }

    // ── expiry ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("an expired request cannot be approved")
    void expiredRequestCannotBeApproved() {
        PrivilegeApproval request = requested(PrivilegeAction.CENTRAL_GRANT, ADMIN_A, USER_Y, central());
        db.backdate(request.approvalId(), 73);
        int before = db.auditRows();

        assertRefused(() -> service.approve(ADMIN_B, request.approvalId(), APPROVAL_REASON),
                Refusal.EXPIRED);
        assertThat(db.status(request.approvalId())).isEqualTo("PENDING");
        assertThat(db.auditRows()).isEqualTo(before);
    }

    @Test
    @DisplayName("an expired approval cannot be executed")
    void expiredApprovalCannotBeExecuted() {
        PrivilegeApproval approval = approved(PrivilegeAction.CENTRAL_GRANT, ADMIN_A, USER_Y, central(), ADMIN_B);
        db.backdate(approval.approvalId(), 73);
        int before = db.auditRows();

        assertRefused(() -> execute(PrivilegeAction.CENTRAL_GRANT, USER_Y, central(), ADMIN_A),
                Refusal.EXPIRED);
        assertThat(db.status(approval.approvalId())).isEqualTo("APPROVED");
        assertThat(db.auditRows()).isEqualTo(before);
    }

    @Test
    @DisplayName("resubmitting after expiry marks the stale row REVOKED by EXPIRED, audited")
    void resubmissionClearsTheExpiredLiveRow() {
        PrivilegeApproval stale = requested(PrivilegeAction.CENTRAL_GRANT, ADMIN_A, USER_Y, central());
        db.backdate(stale.approvalId(), 73);

        PrivilegeApproval fresh = requested(PrivilegeAction.CENTRAL_GRANT, ADMIN_C, USER_Y, central());

        assertThat(fresh.approvalId()).isNotEqualTo(stale.approvalId());
        assertThat(db.status(stale.approvalId())).isEqualTo("REVOKED");
        assertThat(db.jdbc.sql("SELECT revoked_by FROM authz.privilege_approval WHERE approval_id = :id")
                .param("id", stale.approvalId()).query(String.class).single())
                .isEqualTo(PrivilegeApprovalService.EXPIRED_ACTOR);
        assertThat(db.auditRows("PRIVILEGE_APPROVAL_EXPIRED")).isEqualTo(1);
        assertThat(db.auditRows("PRIVILEGE_APPROVAL_REQUESTED")).isEqualTo(2);
    }

    @Test
    @DisplayName("a live, unexpired request blocks a duplicate request for the same target")
    void unexpiredLiveRequestBlocksADuplicate() {
        requested(PrivilegeAction.CENTRAL_GRANT, ADMIN_A, USER_Y, central());
        int before = db.auditRows();
        assertRefused(() -> requested(PrivilegeAction.CENTRAL_GRANT, ADMIN_C, USER_Y, central()),
                Refusal.DUPLICATE_LIVE_REQUEST);
        assertThat(db.auditRows()).isEqualTo(before);
    }

    // ── approver eligibility at execution ───────────────────────────────

    @Test
    @DisplayName("approver loses ARCHIVE_ADMIN between approval and execution: refused")
    void approverWhoLosesAdministrationVoidsTheApproval() {
        PrivilegeApproval approval = approved(PrivilegeAction.CENTRAL_GRANT, ADMIN_A, USER_Y, central(), ADMIN_B);
        db.revokeAdministrator(ADMIN_B.value());
        int before = db.auditRows();

        assertRefused(() -> execute(PrivilegeAction.CENTRAL_GRANT, USER_Y, central(), ADMIN_A),
                Refusal.APPROVER_INELIGIBLE);
        assertThat(db.status(approval.approvalId())).isEqualTo("APPROVED");
        assertThat(db.auditRows()).isEqualTo(before);
    }

    @Test
    @DisplayName("approver suspended between approval and execution: refused, also by the SQL alone")
    void suspendedApproverVoidsTheApproval() {
        PrivilegeApproval approval = approved(PrivilegeAction.CENTRAL_GRANT, ADMIN_A, USER_Y, central(), ADMIN_B);
        db.suspend(ADMIN_B.value());

        assertRefused(() -> execute(PrivilegeAction.CENTRAL_GRANT, USER_Y, central(), ADMIN_A),
                Refusal.APPROVER_INELIGIBLE);
        Boolean consumed = new TransactionTemplate(db.tx).execute(s -> db.approvals.consume(
                approval.approvalId(), PrivilegeAction.CENTRAL_GRANT, USER_Y, central(), ADMIN_A.value()));
        assertThat(consumed).as("the conditional UPDATE re-checks the approver itself").isFalse();
        assertThat(db.status(approval.approvalId())).isEqualTo("APPROVED");
    }

    // ── transactions and concurrency ────────────────────────────────────

    @Test
    @DisplayName("a consumption in a rolled-back transaction leaves the approval APPROVED, unaudited")
    void consumptionRollsBackWithTheWrite() {
        PrivilegeApproval approval = approved(PrivilegeAction.CENTRAL_GRANT, ADMIN_A, USER_Y, central(), ADMIN_B);
        int before = db.auditRows();

        new TransactionTemplate(db.tx).executeWithoutResult(status -> {
            service.requireConsumedApproval(PrivilegeAction.CENTRAL_GRANT, USER_Y, central(), ADMIN_A);
            assertThat(db.status(approval.approvalId())).as("consumed inside").isEqualTo("CONSUMED");
            // The protected write fails after the consume (e.g. a V088 duplicate).
            status.setRollbackOnly();
        });

        assertThat(db.status(approval.approvalId())).isEqualTo("APPROVED");
        assertThat(db.auditRows()).isEqualTo(before);
        assertThat(execute(PrivilegeAction.CENTRAL_GRANT, USER_Y, central(), ADMIN_A).status())
                .as("and it can still be used once")
                .isEqualTo(PrivilegeApproval.Status.CONSUMED);
    }

    @Test
    @DisplayName("a caught refusal does not poison the caller's transaction (bulk path)")
    void aCaughtRefusalLeavesTheCallersTransactionUsable() {
        approved(PrivilegeAction.CENTRAL_GRANT, ADMIN_A, USER_Y, central(), ADMIN_B);
        InstitutionalIdentifier unapproved = new InstitutionalIdentifier("SYN-USER-Z");

        // One transaction, two grantees: Z has no approval and is refused and
        // skipped; Y's approval must still be consumed and COMMITTED.
        new TransactionTemplate(db.tx).executeWithoutResult(status -> {
            assertRefused(() -> service.requireConsumedApproval(
                    PrivilegeAction.CENTRAL_GRANT, unapproved, central(), ADMIN_A), Refusal.NO_APPROVAL);
            service.requireConsumedApproval(PrivilegeAction.CENTRAL_GRANT, USER_Y, central(), ADMIN_A);
        });

        assertThat(db.jdbc.sql("SELECT status FROM authz.privilege_approval")
                .query(String.class).single()).isEqualTo("CONSUMED");
        assertThat(db.auditRows("PRIVILEGE_APPROVAL_CONSUMED")).isEqualTo(1);
    }

    @Test
    @DisplayName("N concurrent executors against one approval: exactly one succeeds")
    void concurrentConsumersExactlyOneSucceeds() throws Exception {
        PrivilegeApproval approval = approved(PrivilegeAction.CENTRAL_GRANT, ADMIN_A, USER_Y, central(), ADMIN_B);
        int threads = 8;
        List<Object> results = race(threads, () ->
                execute(PrivilegeAction.CENTRAL_GRANT, USER_Y, central(), ADMIN_A));

        assertThat(results.stream().filter(PrivilegeApproval.class::isInstance).count()).isEqualTo(1);
        assertThat(results.stream().filter(PrivilegeApprovalException.class::isInstance).count())
                .isEqualTo(threads - 1);
        assertThat(db.status(approval.approvalId())).isEqualTo("CONSUMED");
        assertThat(db.auditRows("PRIVILEGE_APPROVAL_CONSUMED")).isEqualTo(1);
    }

    @Test
    @DisplayName("concurrent requests for the same target leave exactly one live row")
    void concurrentRequestsLeaveOneLiveRow() throws Exception {
        int threads = 8;
        List<Object> results = race(threads, () ->
                requested(PrivilegeAction.CENTRAL_GRANT, ADMIN_A, USER_Y, central()));

        assertThat(results.stream().filter(PrivilegeApproval.class::isInstance).count()).isEqualTo(1);
        assertThat(results).filteredOn(PrivilegeApprovalException.class::isInstance)
                .hasSize(threads - 1)
                .allSatisfy(e -> assertThat(((PrivilegeApprovalException) e).refusal())
                        .isEqualTo(Refusal.DUPLICATE_LIVE_REQUEST));
        assertThat(db.jdbc.sql("""
                        SELECT count(*) FROM authz.privilege_approval
                         WHERE status IN ('PENDING', 'APPROVED')
                        """).query(Integer.class).single()).isEqualTo(1);
        assertThat(db.auditRows("PRIVILEGE_APPROVAL_REQUESTED")).isEqualTo(1);
    }

    /** Run the call on N threads released together; collect each result or exception. */
    private static List<Object> race(int threads, Callable<Object> call) throws Exception {
        CyclicBarrier start = new CyclicBarrier(threads);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<Future<Object>> futures = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                futures.add(pool.submit(() -> {
                    start.await(10, TimeUnit.SECONDS);
                    try {
                        return call.call();
                    } catch (PrivilegeApprovalException refused) {
                        return refused;
                    }
                }));
            }
            List<Object> results = new ArrayList<>();
            for (Future<Object> f : futures) {
                results.add(f.get(60, TimeUnit.SECONDS));
            }
            return results;
        } finally {
            pool.shutdownNow();
        }
    }
}
