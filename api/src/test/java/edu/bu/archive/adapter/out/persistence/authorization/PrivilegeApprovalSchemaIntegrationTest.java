package edu.bu.archive.adapter.out.persistence.authorization;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Statement;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * V090's own guarantees, exercised with direct SQL so that no Java check can
 * be what makes a test pass: the two-person and not-subject CHECKs, the
 * immutability trigger (3.2a), the legal transitions, the 72-hour window
 * (3.2b), the binding shape, the live unique index, append-only, and the
 * hand-run rollback.
 */
@Tag("database")
@Testcontainers(disabledWithoutDocker = true)
class PrivilegeApprovalSchemaIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg17").asCompatibleSubstituteFor("postgres"));

    private static PrivilegeApprovalTestDatabase db;

    @BeforeAll
    static void setUp() throws Exception {
        db = PrivilegeApprovalTestDatabase.create(postgres);
    }

    @BeforeEach
    void reset() {
        db.reset();
        // The trigger checks the approver is an active administrator (QA M1),
        // so the approvers these rows name must exist.
        db.seedAdministrator("SYN-B");
        db.seedAdministrator("SYN-C");
    }

    /** A PENDING ADMIN_ASSIGN row: requester SYN-A, subject SYN-S. */
    private static long pending() {
        return db.jdbc.sql("""
                INSERT INTO authz.privilege_approval
                    (action, subject_identifier, requested_by, reason, valid_until)
                VALUES ('ADMIN_ASSIGN', 'SYN-S', 'SYN-A', 'synthetic schema test request', now())
                RETURNING approval_id
                """).query(Long.class).single();
    }

    private static long approved() {
        long id = pending();
        sql("UPDATE authz.privilege_approval SET status = 'APPROVED', approved_by = 'SYN-B', "
                + "approval_reason = 'approved in a schema test' "
                + "WHERE approval_id = " + id);
        return id;
    }

    private static void sql(String statement) {
        db.jdbc.sql(statement).update();
    }

    // ── the two identity CHECKs ─────────────────────────────────────────

    @Test
    @DisplayName("direct SQL approval by the requester hits ck_pa_two_people")
    void requesterApprovalHitsTheCheck() {
        // An eligible administrator, so the trigger's eligibility rule passes
        // and it is the CHECK itself that refuses.
        db.seedAdministrator("SYN-A");
        long id = pending();
        assertThatThrownBy(() -> sql("UPDATE authz.privilege_approval SET status = 'APPROVED', "
                + "approved_by = 'SYN-A', "
                + "approval_reason = 'approving my own request' WHERE approval_id = " + id))
                .hasMessageContaining("ck_pa_two_people");
        assertThat(db.status(id)).isEqualTo("PENDING");
    }

    @Test
    @DisplayName("direct SQL approval by the beneficiary hits ck_pa_not_subject")
    void beneficiaryApprovalHitsTheCheck() {
        db.seedAdministrator("SYN-S");
        long id = pending();
        assertThatThrownBy(() -> sql("UPDATE authz.privilege_approval SET status = 'APPROVED', "
                + "approved_by = 'SYN-S', "
                + "approval_reason = 'approving my own escalation' WHERE approval_id = " + id))
                .hasMessageContaining("ck_pa_not_subject");
        assertThat(db.status(id)).isEqualTo("PENDING");
    }

    // ── immutability (3.2a) ─────────────────────────────────────────────

    static Stream<Arguments> boundColumnChanges() {
        return Stream.of(
                Arguments.of("action", "action = 'ADMIN_RESTORE'"),
                Arguments.of("subject_identifier", "subject_identifier = 'SYN-OTHER'"),
                Arguments.of("grant_type", "grant_type = 'CENTRAL'"),
                Arguments.of("unit_number", "unit_number = 'SYN-U-1'"),
                Arguments.of("io_value", "io_value = 'SYN-IO-1'"),
                Arguments.of("award_number", "award_number = 'SYN-AWD-1'"),
                Arguments.of("include_descendants", "include_descendants = TRUE"),
                Arguments.of("expires_at", "expires_at = now() + interval '1 year'"),
                Arguments.of("target_grant_id", "target_grant_id = 1"),
                Arguments.of("cognito_issuer", "cognito_issuer = 'https://synthetic'"),
                Arguments.of("cognito_subject", "cognito_subject = 'synthetic-sub'"),
                Arguments.of("proposed_kuali_person_id", "proposed_kuali_person_id = 'SYN-KP-0001'"),
                Arguments.of("requested_by", "requested_by = 'SYN-OTHER'"),
                Arguments.of("requested_at", "requested_at = requested_at - interval '1 hour'"),
                Arguments.of("reason", "reason = 'a different reason entirely'"),
                Arguments.of("valid_until", "valid_until = valid_until + interval '1 year'"),
                Arguments.of("approval_id", "approval_id = approval_id + 100000"));
    }

    @ParameterizedTest(name = "UPDATE of {0} on a PENDING row raises")
    @MethodSource("boundColumnChanges")
    void boundColumnsAreFrozenWhilePending(String column, String assignment) {
        long id = pending();
        assertThatThrownBy(() -> sql("UPDATE authz.privilege_approval SET " + assignment
                + " WHERE approval_id = " + id))
                .hasMessageContaining("immutable");
    }

    @ParameterizedTest(name = "UPDATE of {0} on an APPROVED row raises")
    @MethodSource("boundColumnChanges")
    void boundColumnsAreFrozenOnceApproved(String column, String assignment) {
        long id = approved();
        assertThatThrownBy(() -> sql("UPDATE authz.privilege_approval SET " + assignment
                + " WHERE approval_id = " + id))
                .hasMessageContaining("immutable");
        assertThat(db.status(id)).isEqualTo("APPROVED");
    }

    @Test
    @DisplayName("a LINK_PRIVILEGED row's bound principal cannot be changed, set or cleared after approval")
    void theBoundPrincipalCannotBeUpdatedAfterApproval() {
        long withPrincipal = linkRow("SYN-S-P1", "'SYN-KP-0001'");
        long withoutPrincipal = linkRow("SYN-S-P2", "NULL");

        for (String change : new String[] {"'SYN-KP-0002'", "NULL"}) {
            assertThatThrownBy(() -> sql("UPDATE authz.privilege_approval SET proposed_kuali_person_id = "
                    + change + " WHERE approval_id = " + withPrincipal))
                    .hasMessageContaining("immutable");
        }
        assertThatThrownBy(() -> sql("UPDATE authz.privilege_approval "
                + "SET proposed_kuali_person_id = 'SYN-KP-0003' WHERE approval_id = " + withoutPrincipal))
                .as("null -> value is a change too")
                .hasMessageContaining("immutable");
        // Not even folded into a legal transition.
        assertThatThrownBy(() -> sql("UPDATE authz.privilege_approval SET status = 'CONSUMED', "
                + "consumed_by = 'SYN-A', proposed_kuali_person_id = 'SYN-KP-0004' "
                + "WHERE approval_id = " + withPrincipal))
                .hasMessageContaining("immutable");

        assertThat(db.jdbc.sql("SELECT proposed_kuali_person_id FROM authz.privilege_approval "
                + "WHERE approval_id = " + withPrincipal).query(String.class).single())
                .isEqualTo("SYN-KP-0001");
        assertThat(db.status(withPrincipal)).isEqualTo("APPROVED");
        assertThat(db.status(withoutPrincipal)).isEqualTo("APPROVED");
    }

    @Test
    @DisplayName("the principal is part of the live key and is refused on non-link actions")
    void principalIsKeyedAndShapeChecked() {
        linkRow("SYN-S-P3", "'SYN-KP-0001'");
        assertThatCode(() -> linkRow("SYN-S-P3", "'SYN-KP-0002'"))
                .as("a different principal is a different target")
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> linkRow("SYN-S-P3", "'SYN-KP-0001'"))
                .hasMessageContaining("ux_privilege_approval_live");
        assertThatThrownBy(() -> sql("""
                INSERT INTO authz.privilege_approval
                    (action, subject_identifier, requested_by, reason, valid_until, proposed_kuali_person_id)
                VALUES ('ADMIN_ASSIGN', 'SYN-S', 'SYN-A', 'principal on a non-link action', now(), 'SYN-KP-1')
                """)).hasMessageContaining("ck_pa_binding_shape");
    }

    /** An APPROVED LINK_PRIVILEGED row for {@code subject} with the given principal SQL literal. */
    private static long linkRow(String subject, String principalLiteral) {
        long id = db.jdbc.sql("INSERT INTO authz.privilege_approval "
                + "(action, subject_identifier, cognito_issuer, cognito_subject, proposed_kuali_person_id, "
                + " requested_by, reason, valid_until) VALUES ('LINK_PRIVILEGED', '" + subject + "', "
                + "'https://synthetic-issuer', 'synthetic-sub', " + principalLiteral + ", 'SYN-A', "
                + "'link request in a schema test', now()) RETURNING approval_id")
                .query(Long.class).single();
        sql("UPDATE authz.privilege_approval SET status = 'APPROVED', approved_by = 'SYN-B', "
                + "approval_reason = 'approved in a schema test' "
                + "WHERE approval_id = " + id);
        return id;
    }

    @Test
    @DisplayName("the approver and approval reason cannot be rewritten when consuming or revoking")
    void approvalColumnsAreFrozenAfterApproval() {
        long id = approved();
        assertThatThrownBy(() -> sql("UPDATE authz.privilege_approval SET status = 'CONSUMED', "
                + "consumed_by = 'SYN-A', approved_by = 'SYN-C' "
                + "WHERE approval_id = " + id))
                .hasMessageContaining("only the consumption columns");
        assertThatThrownBy(() -> sql("UPDATE authz.privilege_approval SET status = 'REVOKED', "
                + "revoked_by = 'SYN-A', revocation_reason = 'withdrawn by test', "
                + "approval_reason = 'rewritten justification' WHERE approval_id = " + id))
                .hasMessageContaining("only the revocation columns");
        assertThat(db.status(id)).isEqualTo("APPROVED");
    }

    // ── legal transitions only ──────────────────────────────────────────

    static Stream<Arguments> illegalTransitions() {
        String approve = "status = 'APPROVED', approved_by = 'SYN-B', "
                + "approval_reason = 'approved in a schema test'";
        return Stream.of(
                Arguments.of("PENDING -> CONSUMED", "PENDING",
                        "status = 'CONSUMED', consumed_by = 'SYN-A'"),
                Arguments.of("APPROVED -> PENDING", "APPROVED", "status = 'PENDING'"),
                Arguments.of("APPROVED -> APPROVED (re-approval)", "APPROVED",
                        "approved_by = 'SYN-C'"),
                Arguments.of("CONSUMED -> APPROVED", "CONSUMED", "status = 'APPROVED', "
                        + "consumed_at = NULL, consumed_by = NULL"),
                Arguments.of("CONSUMED -> REVOKED", "CONSUMED", "status = 'REVOKED', "
                        + "consumed_at = NULL, consumed_by = NULL, "
                        + "revoked_by = 'SYN-A', revocation_reason = 'revoking a used approval'"),
                Arguments.of("REVOKED -> APPROVED", "REVOKED", approve.replace("approved_by = 'SYN-B'",
                        "approved_by = 'SYN-C'") + ", revoked_at = NULL, revoked_by = NULL, "
                        + "revocation_reason = NULL"),
                Arguments.of("REVOKED -> PENDING", "REVOKED", "status = 'PENDING', revoked_at = NULL, "
                        + "revoked_by = NULL, revocation_reason = NULL"));
    }

    @ParameterizedTest(name = "{0} raises")
    @MethodSource("illegalTransitions")
    void illegalTransitionsRaise(String name, String from, String assignment) {
        long id = rowIn(from);
        assertThatThrownBy(() -> sql("UPDATE authz.privilege_approval SET " + assignment
                + " WHERE approval_id = " + id))
                .hasMessageContaining("illegal privilege approval transition");
        assertThat(db.status(id)).isEqualTo(from);
    }

    private static long rowIn(String status) {
        return switch (status) {
            case "PENDING" -> pending();
            case "APPROVED" -> approved();
            case "CONSUMED" -> {
                long id = approved();
                sql("UPDATE authz.privilege_approval SET status = 'CONSUMED', "
                        + "consumed_by = 'SYN-A' WHERE approval_id = " + id);
                yield id;
            }
            case "REVOKED" -> {
                long id = pending();
                sql("UPDATE authz.privilege_approval SET status = 'REVOKED', "
                        + "revoked_by = 'SYN-A', revocation_reason = 'withdrawn in schema test' "
                        + "WHERE approval_id = " + id);
                yield id;
            }
            default -> throw new IllegalArgumentException(status);
        };
    }

    @Test
    @DisplayName("the four legal transitions are accepted")
    void legalTransitionsAreAccepted() {
        assertThat(db.status(rowIn("CONSUMED"))).isEqualTo("CONSUMED");
        assertThat(db.status(rowIn("REVOKED"))).isEqualTo("REVOKED");
        long approvedThenRevoked = approved();
        assertThatCode(() -> sql("UPDATE authz.privilege_approval SET status = 'REVOKED', "
                + "revoked_by = 'SYN-A', revocation_reason = 'withdrawn after approval' "
                + "WHERE approval_id = " + approvedThenRevoked)).doesNotThrowAnyException();
    }

    // ── creation, window and shape ──────────────────────────────────────

    @Test
    @DisplayName("valid_until is always requested_at + 72h, whatever the INSERT supplied")
    void validUntilIsSetByTheDatabase() {
        long id = db.jdbc.sql("""
                INSERT INTO authz.privilege_approval
                    (action, subject_identifier, requested_by, reason, valid_until)
                VALUES ('ADMIN_ASSIGN', 'SYN-S', 'SYN-A', 'trying to choose a longer window',
                        now() + interval '10 years')
                RETURNING approval_id
                """).query(Long.class).single();
        Boolean exact = db.jdbc.sql("""
                SELECT valid_until = requested_at + interval '72 hours'
                  FROM authz.privilege_approval WHERE approval_id = :id
                """).param("id", id).query(Boolean.class).single();
        assertThat(exact).isTrue();
    }

    @Test
    @DisplayName("a future-dated request is refused (it would stretch its own window)")
    void futureRequestedAtIsRefused() {
        assertThatThrownBy(() -> sql("""
                INSERT INTO authz.privilege_approval
                    (action, subject_identifier, requested_by, reason, requested_at, valid_until)
                VALUES ('ADMIN_ASSIGN', 'SYN-S', 'SYN-A', 'a request from the future',
                        now() + interval '1 day', now())
                """)).hasMessageContaining("requested_at is set by the database");
    }

    @Test
    @DisplayName("a row cannot be created already APPROVED")
    void rowsAreCreatedPending() {
        assertThatThrownBy(() -> sql("""
                INSERT INTO authz.privilege_approval
                    (action, subject_identifier, requested_by, reason, valid_until,
                     status, approved_by, approved_at, approval_reason)
                VALUES ('ADMIN_ASSIGN', 'SYN-S', 'SYN-A', 'pre-approved by an insert', now(),
                        'APPROVED', 'SYN-B', now(), 'approved by the same insert')
                """)).hasMessageContaining("must be created PENDING");
    }

    @Test
    @DisplayName("a reason under 10 characters is refused by the CHECK")
    void shortReasonHitsTheCheck() {
        assertThatThrownBy(() -> sql("""
                INSERT INTO authz.privilege_approval
                    (action, subject_identifier, requested_by, reason, valid_until)
                VALUES ('ADMIN_ASSIGN', 'SYN-S', 'SYN-A', '  short  ', now())
                """)).hasMessageContaining("ck_pa_reason");
    }

    @Test
    @DisplayName("an approval vaguer than its action is refused (ck_pa_binding_shape)")
    void bindingShapeIsEnforced() {
        assertThatThrownBy(() -> sql("""
                INSERT INTO authz.privilege_approval
                    (action, subject_identifier, requested_by, reason, valid_until)
                VALUES ('CENTRAL_GRANT', 'SYN-S', 'SYN-A', 'central grant without grant columns', now())
                """)).hasMessageContaining("ck_pa_binding_shape");
        assertThatThrownBy(() -> sql("""
                INSERT INTO authz.privilege_approval
                    (action, subject_identifier, requested_by, reason, valid_until)
                VALUES ('LINK_PRIVILEGED', 'SYN-S', 'SYN-A', 'link without naming the sign-in', now())
                """)).hasMessageContaining("ck_pa_binding_shape");
    }

    @Test
    @DisplayName("one live row per target; a revoked one no longer blocks")
    void liveUniqueIndex() {
        long first = pending();
        assertThatThrownBy(PrivilegeApprovalSchemaIntegrationTest::pending)
                .hasMessageContaining("ux_privilege_approval_live");
        sql("UPDATE authz.privilege_approval SET status = 'REVOKED', "
                + "revoked_by = 'SYN-A', revocation_reason = 'withdrawn in schema test' "
                + "WHERE approval_id = " + first);
        assertThatCode(PrivilegeApprovalSchemaIntegrationTest::pending).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("DELETE is refused by the trigger, even for the owner")
    void deleteIsRefused() {
        long id = approved();
        assertThatThrownBy(() -> sql("DELETE FROM authz.privilege_approval WHERE approval_id = " + id))
                .hasMessageContaining("append-only");
        assertThat(db.status(id)).isEqualTo("APPROVED");
    }

    // ── rollback ────────────────────────────────────────────────────────

    @Test
    @DisplayName("the rollback script refuses while rows exist, and drops cleanly when empty")
    void rollbackScript() throws Exception {
        pending();
        String rollback = Files.readString(locateRollback());
        try (Connection c = db.dataSource.getConnection(); Statement s = c.createStatement()) {
            assertThatThrownBy(() -> s.execute(rollback))
                    .hasMessageContaining("Refusing to roll back V090");
            s.execute("ROLLBACK");
        }
        assertThat(exists()).isTrue();

        db.reset();
        try (Connection c = db.dataSource.getConnection(); Statement s = c.createStatement()) {
            s.execute(rollback);
        }
        assertThat(exists()).isFalse();

        // Re-apply so the rest of the class still has its table.
        Path v090 = PrivilegeApprovalTestDatabase.migrations(
                PrivilegeApprovalTestDatabase.locate("migrations-pending-approval")).stream()
                .filter(p -> p.getFileName().toString().startsWith("V090__"))
                .findFirst().orElseThrow();
        try (Connection c = db.dataSource.getConnection(); Statement s = c.createStatement()) {
            s.execute(Files.readString(v090));
        }
        assertThat(exists()).isTrue();
    }

    private static boolean exists() {
        return db.jdbc.sql("SELECT to_regclass('authz.privilege_approval') IS NOT NULL")
                .query(Boolean.class).single();
    }

    private static Path locateRollback() {
        Path c = Path.of("database", "rollback", "V090__privilege_approval_rollback.sql");
        return Files.exists(c) ? c
                : Path.of("..", "database", "rollback", "V090__privilege_approval_rollback.sql");
    }
}
