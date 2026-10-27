package edu.bu.archive.application.authorization.admin;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import edu.bu.archive.application.authorization.AccessGrant;
import edu.bu.archive.application.authorization.GrantType;
import edu.bu.archive.application.authorization.InstitutionalIdentifier;
import edu.bu.archive.application.authorization.KualiPersonId;
import edu.bu.archive.application.authorization.fixtures.InMemoryAccessGrantRepository;
import edu.bu.archive.application.authorization.fixtures.InMemoryAdminAuditRepository;
import edu.bu.archive.application.authorization.fixtures.InMemoryAdminIdentityRepository;
import edu.bu.archive.application.authorization.fixtures.InMemoryAdminRoleRepository;
import edu.bu.archive.application.authorization.fixtures.RecordingAdminAuditSink;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Grants, identity links and suspension, through the one service that is
 * allowed to change them.
 *
 * <p>These tests encode the approved architecture rather than only the
 * mechanics: administration is not data access, historical KRIM status does
 * not decide current access, and nothing changes without an audit row.
 */
class ArchiveAdminAccessManagementTest {

    private static final InstitutionalIdentifier ADMIN = new InstitutionalIdentifier("inst-admin");
    private static final InstitutionalIdentifier SUBJECT = new InstitutionalIdentifier("inst-subject");
    private static final InstitutionalIdentifier OUTSIDER = new InstitutionalIdentifier("inst-outsider");

    private InMemoryAdminRoleRepository adminRoles;
    private RecordingAdminAuditSink audit;
    private InMemoryAccessGrantRepository grants;
    private InMemoryAdminIdentityRepository identities;
    private ArchiveAdminService service;

    @BeforeEach
    void setUp() {
        adminRoles = new InMemoryAdminRoleRepository();
        audit = new RecordingAdminAuditSink();
        grants = new InMemoryAccessGrantRepository();
        identities = new InMemoryAdminIdentityRepository();
        service = new ArchiveAdminService(adminRoles, audit, grants, identities,
                new InMemoryAdminAuditRepository());
        service.bootstrapFirstAdministrator(ADMIN, "operator@bu.edu", "test");
        audit.clear();
    }

    @Nested
    @DisplayName("Administration is not data access")
    class AdministrationIsNotDataAccess {

        @Test
        void anAdministratorWithNoGrantHasNoArchiveAccess() {
            IdentityOverview overview = service.describeIdentity(ADMIN, ADMIN);

            assertThat(overview.administrator()).isTrue();
            assertThat(overview.grants()).isEmpty();
            assertThat(overview.hasNoArchiveAccess())
                    .as("holding ARCHIVE_ADMIN must confer no archive data access")
                    .isTrue();
        }

        @Test
        void assigningTheAdminRoleCreatesNoGrant() {
            service.assignAdministrator(ADMIN, SUBJECT, "second administrator");

            assertThat(grants.findForGrantee(SUBJECT))
                    .as("the role must never imply a data grant")
                    .isEmpty();
        }

        @Test
        void grantingAccessToOneselfIsPossibleButExplicitAndAudited() {
            service.grantAccess(ADMIN, ADMIN, central("needed for support"));

            assertThat(grants.findForGrantee(ADMIN)).hasSize(1);
            assertThat(audit.events())
                    .singleElement()
                    .satisfies(entry -> assertThat(entry.action())
                            .isEqualTo(AdminAuditAction.ACCESS_GRANTED));
        }
    }

    @Nested
    @DisplayName("Only an administrator may change anything")
    class OnlyAnAdministrator {

        @Test
        void anOrdinaryIdentityCannotGrant() {
            assertThatThrownBy(() -> service.grantAccess(OUTSIDER, SUBJECT, central(null)))
                    .isInstanceOf(NotAnArchiveAdministratorException.class);
            assertThat(grants.findForGrantee(SUBJECT)).isEmpty();
            assertThat(audit.events()).isEmpty();
        }

        @Test
        void anOrdinaryIdentityCannotRevokeSuspendLinkOrRead() {
            AccessGrant existing = service.grantAccess(ADMIN, SUBJECT, central(null));
            audit.clear();

            assertThatThrownBy(() -> service.revokeAccess(OUTSIDER, existing.id(), null))
                    .isInstanceOf(NotAnArchiveAdministratorException.class);
            assertThatThrownBy(() -> service.setSuspended(OUTSIDER, SUBJECT, true, null))
                    .isInstanceOf(NotAnArchiveAdministratorException.class);
            assertThatThrownBy(() -> service.linkIdentity(OUTSIDER, "iss", "sub", SUBJECT, null, null, null))
                    .isInstanceOf(NotAnArchiveAdministratorException.class);
            assertThatThrownBy(() -> service.describeIdentity(OUTSIDER, SUBJECT))
                    .isInstanceOf(NotAnArchiveAdministratorException.class);
            assertThatThrownBy(() -> service.searchIdentities(OUTSIDER, "any", 10))
                    .isInstanceOf(NotAnArchiveAdministratorException.class);
            assertThatThrownBy(() -> service.auditHistory(OUTSIDER, null))
                    .isInstanceOf(NotAnArchiveAdministratorException.class);

            assertThat(audit.events())
                    .as("a refused action must not leave an audit row")
                    .isEmpty();
        }
    }

    @Nested
    @DisplayName("Grant lifecycle")
    class GrantLifecycle {

        @Test
        void grantsDoNotExpireUnlessAnExpiryIsSet() {
            AccessGrant granted = service.grantAccess(ADMIN, SUBJECT, central(null));

            assertThat(granted.expiresAt())
                    .as("D-ADM-3: no automatic expiry")
                    .isNull();
            assertThat(granted.isActiveAt(Instant.parse("2099-01-01T00:00:00Z"))).isTrue();
        }

        @Test
        void revokingIsAStatusChangeNotADelete() {
            AccessGrant granted = service.grantAccess(ADMIN, SUBJECT, central(null));

            service.revokeAccess(ADMIN, granted.id(), "left the department");

            assertThat(grants.findForGrantee(SUBJECT))
                    .as("the row is the evidence the access existed")
                    .hasSize(1)
                    .allSatisfy(grant -> assertThat(grant.revokedAt()).isNotNull());
        }

        @Test
        void revokingTwiceIsRefusedRatherThanSilentlySucceeding() {
            AccessGrant granted = service.grantAccess(ADMIN, SUBJECT, central(null));
            service.revokeAccess(ADMIN, granted.id(), null);

            assertThatThrownBy(() -> service.revokeAccess(ADMIN, granted.id(), null))
                    .isInstanceOf(java.util.NoSuchElementException.class);
        }

        @Test
        void anIoGrantCannotIncludeDescendants() {
            assertThatThrownBy(() -> new GrantSpecification(
                    GrantType.IO, null, true, "9500012345", null, null))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("descendants");
        }

        @Test
        void aUnitGrantRequiresAUnitAndAnIoGrantRequiresAnIoValue() {
            assertThatThrownBy(() -> new GrantSpecification(GrantType.UNIT, null, false, null, null, null))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> new GrantSpecification(GrantType.IO, null, false, null, null, null))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    @DisplayName("Historical KRIM status does not decide current access")
    class HistoricalStatusIsNotAuthorization {

        @Test
        void anIdentityMayBeLinkedToAPrincipalKrimMarksInactive() {
            // D-ID-1. 61% of referenced principals are inactive in KRIM; refusing
            // them here would deny most legitimate archive users.
            assertThatCode(() -> service.linkIdentity(ADMIN, "https://cognito/pool", "cognito-sub-1",
                    SUBJECT, new KualiPersonId("INACTIVE1"), "someone", "verified in person"))
                    .doesNotThrowAnyException();

            assertThat(service.describeIdentity(ADMIN, SUBJECT).links()).hasSize(1);
        }

        @Test
        void unlinkingRemovesResolutionWithoutTouchingGrants() {
            var link = service.linkIdentity(ADMIN, "https://cognito/pool", "cognito-sub-2",
                    SUBJECT, null, null, null);
            service.grantAccess(ADMIN, SUBJECT, central(null));

            service.unlinkIdentity(ADMIN, link.id(), "left BU");

            IdentityOverview overview = service.describeIdentity(ADMIN, SUBJECT);
            assertThat(overview.links()).noneMatch(l -> l.isActive());
            assertThat(overview.grants()).hasSize(1);
        }
    }

    @Nested
    @DisplayName("Suspension")
    class Suspension {

        @Test
        void suspensionOverridesEveryGrantWithoutRevokingThem() {
            service.grantAccess(ADMIN, SUBJECT, central(null));

            service.setSuspended(ADMIN, SUBJECT, true, "under review");

            IdentityOverview overview = service.describeIdentity(ADMIN, SUBJECT);
            assertThat(overview.suspended()).isTrue();
            assertThat(overview.hasNoArchiveAccess()).isTrue();
            assertThat(overview.grants())
                    .as("restoring must be one action, not a re-grant")
                    .allSatisfy(grant -> assertThat(grant.revokedAt()).isNull());
        }

        @Test
        void restoringClearsSuspension() {
            service.grantAccess(ADMIN, SUBJECT, central(null));
            service.setSuspended(ADMIN, SUBJECT, true, "under review");

            service.setSuspended(ADMIN, SUBJECT, false, "cleared");

            assertThat(service.describeIdentity(ADMIN, SUBJECT).hasNoArchiveAccess()).isFalse();
        }
    }

    @Nested
    @DisplayName("Every change is audited")
    class EveryChangeIsAudited {

        @Test
        void eachMutationWritesExactlyOneAuditRowWithTheRightAction() {
            AccessGrant granted = service.grantAccess(ADMIN, SUBJECT, central(null));
            service.modifyAccess(ADMIN, granted.id(), Instant.parse("2030-01-01T00:00:00Z"), false, null);
            service.revokeAccess(ADMIN, granted.id(), null);
            var link = service.linkIdentity(ADMIN, "iss", "sub", SUBJECT, null, null, null);
            service.unlinkIdentity(ADMIN, link.id(), null);
            service.setSuspended(ADMIN, SUBJECT, true, null);
            service.setSuspended(ADMIN, SUBJECT, false, null);
            service.assignAdministrator(ADMIN, SUBJECT, null);
            service.revokeAdministrator(ADMIN, SUBJECT, null);

            assertThat(audit.events())
                    .extracting(RecordingAdminAuditSink.Event::action)
                    .containsExactly(
                            AdminAuditAction.ACCESS_GRANTED,
                            AdminAuditAction.ACCESS_MODIFIED,
                            AdminAuditAction.ACCESS_REVOKED,
                            AdminAuditAction.IDENTITY_LINKED,
                            AdminAuditAction.IDENTITY_UNLINKED,
                            AdminAuditAction.IDENTITY_SUSPENDED,
                            AdminAuditAction.IDENTITY_RESTORED,
                            AdminAuditAction.ADMIN_ROLE_ASSIGNED,
                            AdminAuditAction.ADMIN_ROLE_REVOKED);
        }

        @Test
        void theAuditDetailRecordsWhatWasAuthorizedNotJustTheGrantId() {
            service.grantAccess(ADMIN, SUBJECT,
                    new GrantSpecification(GrantType.UNIT, "1234", true, null, null, "new analyst"));

            assertThat(audit.events())
                    .singleElement()
                    .satisfies(entry -> assertThat(entry.detail())
                            .contains("type=UNIT")
                            .contains("unit=1234")
                            .contains("+sub-units")
                            .contains("new analyst"));
        }

        @Test
        void readOnlyReviewWritesNoAuditRow() {
            service.describeIdentity(ADMIN, SUBJECT);
            service.searchIdentities(ADMIN, "anything", 10);
            service.auditHistory(ADMIN, AuditQuery.forSubject(SUBJECT.value()));

            assertThat(audit.events()).isEmpty();
        }
    }

    @Test
    void readingAnIdentityWithNoLinksAndNoGrantsReportsNoAccess() {
        // Rule 7: authenticating through BU grants nothing on its own.
        IdentityOverview overview = service.describeIdentity(ADMIN, SUBJECT);

        assertThat(overview.links()).isEmpty();
        assertThat(overview.grants()).isEmpty();
        assertThat(overview.administrator()).isFalse();
        assertThat(overview.hasNoArchiveAccess()).isTrue();
    }

    @Test
    void auditHistoryIsReadableByAnAdministrator() {
        service.grantAccess(ADMIN, SUBJECT, central(null));

        List<AuditEntry> history = service.auditHistory(ADMIN, AuditQuery.forSubject(SUBJECT.value()));

        assertThat(history).isNotNull();
    }

    private static GrantSpecification central(String reason) {
        return new GrantSpecification(GrantType.CENTRAL, null, false, null, null, reason);
    }
}
