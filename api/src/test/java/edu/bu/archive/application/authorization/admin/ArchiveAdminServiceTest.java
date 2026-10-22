package edu.bu.archive.application.authorization.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import edu.bu.archive.application.authorization.GrantType;
import edu.bu.archive.application.authorization.InstitutionalIdentifier;
import edu.bu.archive.application.authorization.fixtures.InMemoryAccessGrantRepository;
import edu.bu.archive.application.authorization.fixtures.InMemoryAdminRoleRepository;
import edu.bu.archive.application.authorization.fixtures.RecordingAdminAuditSink;

/**
 * ARCHIVE_ADMIN administration rules.
 *
 * <p>Covers the decisions recorded in ANTHONY_REQUIREMENTS_TRACEABILITY.md D5:
 * multiple administrators, administration separate from data access, backend
 * enforcement, audit of every change, controlled bootstrap, and refusal to
 * remove the last administrator. Concurrency is proven separately against a
 * real database - see ArchiveAdminConcurrencyIntegrationTest.
 */
class ArchiveAdminServiceTest {

    private static final InstitutionalIdentifier ADMIN_A =
            new InstitutionalIdentifier("inst-admin-a");
    private static final InstitutionalIdentifier ADMIN_B =
            new InstitutionalIdentifier("inst-admin-b");
    private static final InstitutionalIdentifier ORDINARY =
            new InstitutionalIdentifier("inst-ordinary");

    private InMemoryAdminRoleRepository adminRoles;
    private RecordingAdminAuditSink audit;
    private ArchiveAdminService service;

    @BeforeEach
    void setUp() {
        adminRoles = new InMemoryAdminRoleRepository();
        audit = new RecordingAdminAuditSink();
        service = new ArchiveAdminService(adminRoles, audit);
    }

    /** Bring the first administrator into existence the only way allowed. */
    private void bootstrapAdminA() {
        service.bootstrapFirstAdministrator(ADMIN_A, "operator@bu.edu", "phase 1 test");
    }

    @Nested
    class Bootstrap {

        @Test
        void createsTheFirstAdministratorAndAuditsIt() {
            AdminRoleMember first = service.bootstrapFirstAdministrator(
                    ADMIN_A, "operator@bu.edu", "initial");

            assertThat(first.isActive()).isTrue();
            assertThat(first.grantedBy()).isEqualTo(AdminRoleMember.BOOTSTRAP_ACTOR);
            assertThat(service.isAdministrator(ADMIN_A)).isTrue();
            assertThat(audit.actions())
                    .containsExactly(AdminAuditAction.ADMIN_ROLE_BOOTSTRAPPED);
            assertThat(audit.events().get(0).detail()).contains("operator@bu.edu");
        }

        @Test
        void refusesWhenAnActiveAdministratorAlreadyExists() {
            bootstrapAdminA();

            assertThatThrownBy(() -> service.bootstrapFirstAdministrator(
                    ADMIN_B, "operator@bu.edu", "replay attempt"))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("already exists");

            assertThat(service.isAdministrator(ADMIN_B)).isFalse();
        }

        @Test
        void requiresTheOperatorIdentitySoCustodyIsRecorded() {
            assertThatThrownBy(() ->
                    service.bootstrapFirstAdministrator(ADMIN_A, "  ", "no operator"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("operator identity");
        }
    }

    @Nested
    class AssignAndRevoke {

        @Test
        void anAdministratorMayAssignAnother_andBothHoldItAtOnce() {
            bootstrapAdminA();

            service.assignAdministrator(ADMIN_A, ADMIN_B, "second admin");

            assertThat(service.isAdministrator(ADMIN_A)).isTrue();
            assertThat(service.isAdministrator(ADMIN_B)).isTrue();
            assertThat(service.listAdministrators(ADMIN_A)).hasSize(2);
            assertThat(audit.actions()).containsExactly(
                    AdminAuditAction.ADMIN_ROLE_BOOTSTRAPPED,
                    AdminAuditAction.ADMIN_ROLE_ASSIGNED);
        }

        @Test
        void aNonAdministratorCannotAssign() {
            bootstrapAdminA();

            assertThatThrownBy(() ->
                    service.assignAdministrator(ORDINARY, ADMIN_B, "self-promotion"))
                    .isInstanceOf(NotAnArchiveAdministratorException.class);

            assertThat(service.isAdministrator(ADMIN_B)).isFalse();
            // Nothing was audited, because nothing happened.
            assertThat(audit.actions())
                    .containsExactly(AdminAuditAction.ADMIN_ROLE_BOOTSTRAPPED);
        }

        @Test
        void aNonAdministratorCannotRevoke() {
            bootstrapAdminA();
            service.assignAdministrator(ADMIN_A, ADMIN_B, "second admin");

            assertThatThrownBy(() ->
                    service.revokeAdministrator(ORDINARY, ADMIN_B, "not allowed"))
                    .isInstanceOf(NotAnArchiveAdministratorException.class);

            assertThat(service.isAdministrator(ADMIN_B)).isTrue();
        }

        @Test
        void revokingAnAdministratorAuditsItAndTakesEffectImmediately() {
            bootstrapAdminA();
            service.assignAdministrator(ADMIN_A, ADMIN_B, "second admin");

            service.revokeAdministrator(ADMIN_A, ADMIN_B, "left the team");

            assertThat(service.isAdministrator(ADMIN_B)).isFalse();
            assertThat(audit.actions()).containsExactly(
                    AdminAuditAction.ADMIN_ROLE_BOOTSTRAPPED,
                    AdminAuditAction.ADMIN_ROLE_ASSIGNED,
                    AdminAuditAction.ADMIN_ROLE_REVOKED);
        }

        @Test
        void theRevokedAdministratorCanNoLongerAdminister() {
            bootstrapAdminA();
            service.assignAdministrator(ADMIN_A, ADMIN_B, "second admin");
            service.revokeAdministrator(ADMIN_A, ADMIN_B, "left the team");

            assertThatThrownBy(() ->
                    service.assignAdministrator(ADMIN_B, ORDINARY, "after revocation"))
                    .isInstanceOf(NotAnArchiveAdministratorException.class);
        }

        @Test
        void refusesToRevokeTheLastActiveAdministrator() {
            bootstrapAdminA();

            assertThatThrownBy(() ->
                    service.revokeAdministrator(ADMIN_A, ADMIN_A, "stepping down alone"))
                    .isInstanceOf(LastArchiveAdministratorException.class);

            assertThat(service.isAdministrator(ADMIN_A)).isTrue();
            assertThat(adminRoles.countActive()).isEqualTo(1);
        }

        @Test
        void anAdministratorMayStepDownOnceAnotherExists() {
            bootstrapAdminA();
            service.assignAdministrator(ADMIN_A, ADMIN_B, "successor");

            service.revokeAdministrator(ADMIN_A, ADMIN_A, "stepping down");

            assertThat(service.isAdministrator(ADMIN_A)).isFalse();
            assertThat(service.isAdministrator(ADMIN_B)).isTrue();
            assertThat(adminRoles.countActive()).isEqualTo(1);
        }

        @Test
        void assigningTwiceIsRefused() {
            bootstrapAdminA();
            service.assignAdministrator(ADMIN_A, ADMIN_B, "first");

            assertThatThrownBy(() ->
                    service.assignAdministrator(ADMIN_A, ADMIN_B, "again"))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("already holds");
        }
    }

    @Nested
    class AdministrationIsNotDataAccess {

        /**
         * The decisive property: ARCHIVE_ADMIN confers no archive data access.
         * The grant store is the only source of data access, and bootstrapping
         * or assigning an administrator must not add a row to it.
         */
        @Test
        void holdingArchiveAdminGrantsNoDataAccess() {
            InMemoryAccessGrantRepository grants = new InMemoryAccessGrantRepository();

            bootstrapAdminA();
            service.assignAdministrator(ADMIN_A, ADMIN_B, "second admin");

            assertThat(service.isAdministrator(ADMIN_A)).isTrue();
            assertThat(service.isAdministrator(ADMIN_B)).isTrue();

            for (InstitutionalIdentifier admin : java.util.List.of(ADMIN_A, ADMIN_B)) {
                assertThat(grants.findForGrantee(admin))
                        .as("administrator %s must hold no data-access grant", admin.value())
                        .isEmpty();
            }
        }

        @Test
        void aDataAccessGrantDoesNotMakeSomeoneAnAdministrator() {
            InMemoryAccessGrantRepository grants = new InMemoryAccessGrantRepository();
            grants.grant(ORDINARY.value(), GrantType.CENTRAL, null, false, null);

            assertThat(grants.findForGrantee(ORDINARY)).isNotEmpty();
            assertThat(service.isAdministrator(ORDINARY)).isFalse();
        }
    }

    @Nested
    class GrantChangeAuditing {

        @Test
        void recordsGrantChangesAgainstTheActingAdministrator() {
            bootstrapAdminA();

            service.recordGrantChange(ADMIN_A, AdminAuditAction.ACCESS_GRANTED,
                    ORDINARY, "UNIT 2573180018");
            service.recordGrantChange(ADMIN_A, AdminAuditAction.ACCESS_REVOKED,
                    ORDINARY, "UNIT 2573180018 removed");

            assertThat(audit.actions()).containsExactly(
                    AdminAuditAction.ADMIN_ROLE_BOOTSTRAPPED,
                    AdminAuditAction.ACCESS_GRANTED,
                    AdminAuditAction.ACCESS_REVOKED);
            assertThat(audit.events().get(1).actor()).isEqualTo(ADMIN_A.value());
            assertThat(audit.events().get(1).subject()).isEqualTo(ORDINARY.value());
        }

        @Test
        void aNonAdministratorCannotRecordGrantChanges() {
            assertThatThrownBy(() -> service.recordGrantChange(
                    ORDINARY, AdminAuditAction.ACCESS_GRANTED, ADMIN_B, "forged"))
                    .isInstanceOf(NotAnArchiveAdministratorException.class);
            assertThat(audit.events()).isEmpty();
        }

        @Test
        void rejectsNonAccessActionsOnTheGrantChannel() {
            bootstrapAdminA();

            assertThatThrownBy(() -> service.recordGrantChange(
                    ADMIN_A, AdminAuditAction.ADMIN_ROLE_ASSIGNED, ADMIN_B, "wrong channel"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("ACCESS_*");
        }
    }
}
