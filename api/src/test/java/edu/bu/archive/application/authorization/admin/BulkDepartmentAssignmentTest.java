package edu.bu.archive.application.authorization.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import edu.bu.archive.application.authorization.GrantType;
import edu.bu.archive.application.authorization.InstitutionalIdentifier;
import edu.bu.archive.application.authorization.fixtures.InMemoryAccessGrantRepository;
import edu.bu.archive.application.authorization.fixtures.InMemoryAdminAuditRepository;
import edu.bu.archive.application.authorization.fixtures.InMemoryAdminIdentityRepository;
import edu.bu.archive.application.authorization.fixtures.InMemoryAdminRoleRepository;
import edu.bu.archive.application.authorization.fixtures.RecordingAdminAuditSink;

/**
 * SEC-002: bulk department assignment.
 *
 * <p>The risk a bulk endpoint carries is that it becomes a looser door than
 * the single one it replaces — skipping the administrator check, the
 * specification validation or the duplicate rule because the loop is "just an
 * optimisation". These tests pin the opposite: the bulk path is the single
 * path, run repeatedly, and every guarantee of the single path survives.
 */
class BulkDepartmentAssignmentTest {

    private static final InstitutionalIdentifier ADMIN = new InstitutionalIdentifier("ARP-ADMIN-0001");
    private static final InstitutionalIdentifier NOT_ADMIN = new InstitutionalIdentifier("ARP-PLAIN-0001");
    private static final InstitutionalIdentifier A = new InstitutionalIdentifier("ARP-SUBJ-0001");
    private static final InstitutionalIdentifier B = new InstitutionalIdentifier("ARP-SUBJ-0002");
    private static final InstitutionalIdentifier C = new InstitutionalIdentifier("ARP-SUBJ-0003");

    private InMemoryAccessGrantRepository grants;
    private RecordingAdminAuditSink audit;
    private ArchiveAdminService service;

    private static GrantSpecification department(String unit) {
        return new GrantSpecification(GrantType.UNIT, unit, false, null, null, "department rollout");
    }

    @BeforeEach
    void setUp() {
        grants = new InMemoryAccessGrantRepository();
        audit = new RecordingAdminAuditSink();
        var roles = new InMemoryAdminRoleRepository();
        roles.bootstrap(ADMIN, "BOOTSTRAP", "test fixture");
        service = new ArchiveAdminService(roles, audit, grants,
                new InMemoryAdminIdentityRepository(), new InMemoryAdminAuditRepository());
    }

    @Test
    @DisplayName("every grantee gets the same scope, and each is audited")
    void everyGranteeGetsTheScope() {
        var report = service.grantAccessInBulk(ADMIN, List.of(A, B, C), department("SYN-U-100"));

        assertThat(report.granted()).isEqualTo(3);
        assertThat(report.failed()).isZero();
        assertThat(report.outcomes()).extracting(ArchiveAdminService.BulkGrantOutcome::subject)
                .containsExactly(A, B, C);

        for (InstitutionalIdentifier subject : List.of(A, B, C)) {
            assertThat(grants.findForGrantee(subject))
                    .as(subject.value() + " must hold the department grant")
                    .hasSize(1);
        }
        assertThat(audit.events())
                .as("one row per grantee plus one batch summary")
                .hasSize(4);
    }

    @Test
    @DisplayName("a non-administrator cannot assign in bulk either")
    void onlyAnAdministratorMayAssign() {
        assertThatThrownBy(() -> service.grantAccessInBulk(NOT_ADMIN, List.of(A), department("SYN-U-100")))
                .isInstanceOf(RuntimeException.class);
        assertThat(grants.findForGrantee(A)).isEmpty();
        assertThat(audit.events())
                .as("a refused bulk assignment must leave nothing behind")
                .isEmpty();
    }

    @Test
    @DisplayName("an invalid specification is refused before anything is written")
    void anInvalidSpecificationIsRefusedUpFront() {
        assertThatThrownBy(() -> new GrantSpecification(GrantType.UNIT, null, false, null, null, "x"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.grantAccessInBulk(ADMIN, List.of(A), null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(grants.findForGrantee(A)).isEmpty();
    }

    @Test
    @DisplayName("someone who already holds the scope is reported, not failed")
    void anExistingHolderIsReportedRatherThanFailing() {
        /*
         * The realistic rollout: a department list where some people were
         * granted last month. Failing the batch for that would make the
         * operation unusable and push operators back to one-at-a-time with
         * no batch record at all.
         *
         * The duplicate is injected rather than created by granting twice,
         * because the in-memory repository does not enforce V087 - the
         * uniqueness lives in the database index, and a fixture that
         * pretended to enforce it would be asserting its own behaviour. What
         * is proved here is the MAPPING: a V087 violation becomes
         * ALREADY_HELD rather than aborting the batch. That a second grant of
         * the same scope actually raises it is proved against a real
         * PostgreSQL in GrantProvisioningIntegrationTest.
         */
        var violation = new java.sql.SQLException(
                "duplicate key value violates unique constraint "
                        + "\"ux_access_grant_active_scope\"", "23505");
        grants.failNextCreateWith(
                new org.springframework.dao.DuplicateKeyException(violation.getMessage(), violation));

        var report = service.grantAccessInBulk(ADMIN, List.of(A, B), department("SYN-U-100"));

        assertThat(report.alreadyHeld())
                .as("the existing holder is reported, not treated as a failure")
                .isEqualTo(1);
        assertThat(report.granted()).isEqualTo(1);
        assertThat(report.failed()).isZero();
        assertThat(report.outcomes())
                .filteredOn(o -> o.status() == ArchiveAdminService.BulkGrantStatus.ALREADY_HELD)
                .extracting(ArchiveAdminService.BulkGrantOutcome::subject)
                .containsExactly(A);
        assertThat(grants.findForGrantee(B))
                .as("the rest of the batch still proceeds")
                .hasSize(1);
    }

    @Test
    @DisplayName("the same identifier twice is de-duplicated, not self-conflicting")
    void repeatedIdentifiersAreDeduplicated() {
        // A pasted list with a duplicate is an operator slip. Letting it
        // through would make the second one a self-inflicted V087 duplicate
        // and report a failure the operator cannot act on.
        var report = service.grantAccessInBulk(ADMIN, List.of(A, A, B), department("SYN-U-100"));

        assertThat(report.outcomes()).hasSize(2);
        assertThat(report.granted()).isEqualTo(2);
        assertThat(report.alreadyHeld()).isZero();
    }

    @Test
    @DisplayName("every row of a batch shares one batch reference")
    void oneBatchReferenceTiesTheRolloutTogether() {
        var report = service.grantAccessInBulk(ADMIN, List.of(A, B), department("SYN-U-100"));

        assertThat(report.batchRef()).isNotBlank();
        assertThat(report.outcomes())
                .extracting(ArchiveAdminService.BulkGrantOutcome::batchRef)
                .containsOnly(report.batchRef());
        assertThat(audit.events())
                .as("the summary row carries the batch reference so the rollout "
                        + "is answerable afterwards")
                .anyMatch(e -> e.detail() != null && e.detail().contains(report.batchRef()));
    }

    @Test
    @DisplayName("an empty list is refused rather than treated as a no-op success")
    void anEmptyListIsRefused() {
        assertThatThrownBy(() -> service.grantAccessInBulk(ADMIN, List.of(), department("SYN-U-100")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("at least one grantee");
    }

    @Test
    @DisplayName("bulk assignment works for every grant type the single path accepts")
    void itIsNotRestrictedToDepartmentGrants() {
        /*
         * Named "department assignment" in the requirement because that is
         * the case that needs it, but the implementation delegates to
         * grantAccess and so cannot be narrower than it. Asserted so a later
         * change that special-cases UNIT here is visible.
         */
        var central = new GrantSpecification(GrantType.CENTRAL, null, false, null, null, "central");
        var report = service.grantAccessInBulk(ADMIN, List.of(A, B), central);
        assertThat(report.granted()).isEqualTo(2);
    }
}
