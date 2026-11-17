package edu.bu.archive.application.authorization.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.function.Consumer;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import edu.bu.archive.application.authorization.AccessGrant;
import edu.bu.archive.application.authorization.GrantType;
import edu.bu.archive.application.authorization.IdentityLink;
import edu.bu.archive.application.authorization.InstitutionalIdentifier;
import edu.bu.archive.application.authorization.fixtures.InMemoryAccessGrantRepository;
import edu.bu.archive.application.authorization.fixtures.InMemoryAdminAuditRepository;
import edu.bu.archive.application.authorization.fixtures.InMemoryAdminIdentityRepository;
import edu.bu.archive.application.authorization.fixtures.InMemoryAdminRoleRepository;
import edu.bu.archive.application.authorization.fixtures.RecordingAdminAuditSink;

/**
 * PR 1 of the A-category controls, at the service: every rule that refuses
 * something refuses it before anything is written, and every success writes
 * exactly one audit row. The SQL-dependent halves (row lock, ownership in
 * the UPDATE, N3) are proved against PostgreSQL in
 * {@code AdminWriteHardeningIntegrationTest}.
 */
class AdminWriteHardeningTest {

    private static final InstitutionalIdentifier ADMIN = new InstitutionalIdentifier("SYN-ADMIN-A");
    private static final InstitutionalIdentifier OTHER_ADMIN = new InstitutionalIdentifier("SYN-ADMIN-B");
    private static final InstitutionalIdentifier ALICE = new InstitutionalIdentifier("SYN-ALICE");
    private static final InstitutionalIdentifier BOB = new InstitutionalIdentifier("SYN-BOB");
    private static final String REASON = "approved by the department chair";

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
        service.bootstrapFirstAdministrator(ADMIN, "operator@bu.edu", "synthetic fixture");
        adminRoles.assign(OTHER_ADMIN, ADMIN.value(), "seeded second administrator");
        audit.clear();
    }

    private static GrantSpecification unit(String unit, boolean descendants, Instant expires) {
        return new GrantSpecification(GrantType.UNIT, unit, descendants, null, null, expires, REASON);
    }

    private AccessGrant seed(InstitutionalIdentifier grantee, boolean descendants, Instant expires) {
        // Seeded through the repository: a self-grant through the service is refused.
        AccessGrant created = grants.create(grantee, unit("1234", descendants, expires), "seed");
        audit.clear();
        return created;
    }

    private void assertNothingWritten() {
        assertThat(audit.events()).as("a refusal writes no audit row").isEmpty();
    }

    // ------------------------------------------------------------------

    @Nested
    @DisplayName("Control 1: no self-granting")
    class SelfGrant {

        @Test
        void aSelfGrantIsRefusedAndWritesNothing() {
            assertThatThrownBy(() -> service.grantAccess(ADMIN, ADMIN, unit("1234", false, null)))
                    .isInstanceOf(SelfAdministrationException.class);
            assertThat(grants.findForGrantee(ADMIN)).isEmpty();
            assertNothingWritten();
        }

        @Test
        void aBulkGrantFailsOnlyTheSelfEntry() {
            var report = service.grantAccessInBulk(ADMIN, List.of(ALICE, ADMIN, BOB),
                    unit("1234", false, null));

            assertThat(report.outcomes())
                    .extracting(ArchiveAdminService.BulkGrantOutcome::status)
                    .containsExactly(ArchiveAdminService.BulkGrantStatus.GRANTED,
                            ArchiveAdminService.BulkGrantStatus.FAILED,
                            ArchiveAdminService.BulkGrantStatus.GRANTED);
            assertThat(report.granted()).isEqualTo(2);
            assertThat(report.failed()).isEqualTo(1);
            assertThat(report.outcomes().get(1).message()).contains("themselves");
            assertThat(grants.findForGrantee(ADMIN)).isEmpty();
            // Two grants plus the batch summary.
            assertThat(audit.events()).hasSize(3);
        }

        @Test
        void selfRevocationIsStillAllowed() {
            AccessGrant own = seed(ADMIN, false, null);
            service.revokeAccess(ADMIN, ADMIN, own.id(), "no longer need this access");
            assertThat(grants.findById(own.id()).orElseThrow().revokedAt()).isNotNull();
            assertThat(audit.events()).hasSize(1);
        }
    }

    @Nested
    @DisplayName("Decision D1: CENTRAL and administrator appointment need approval")
    class ApprovalRequired {

        @Test
        void aCentralGrantIsRefusedForAnyoneAndWritesNothing() {
            assertThatThrownBy(() -> service.grantAccess(ADMIN, ALICE,
                    new GrantSpecification(GrantType.CENTRAL, null, false, null, null, REASON)))
                    .isInstanceOf(ApprovalRequiredException.class);
            assertThat(grants.findForGrantee(ALICE)).isEmpty();
            assertNothingWritten();
        }

        @Test
        void aBulkCentralGrantIsRefusedBeforeAnyRowIncludingTheSummary() {
            assertThatThrownBy(() -> service.grantAccessInBulk(ADMIN, List.of(ALICE, BOB),
                    new GrantSpecification(GrantType.CENTRAL, null, false, null, null, REASON)))
                    .isInstanceOf(ApprovalRequiredException.class);
            assertThat(grants.findForGrantee(ALICE)).isEmpty();
            assertThat(grants.findForGrantee(BOB)).isEmpty();
            assertNothingWritten();
        }

        @Test
        void appointingAnAdministratorIsRefusedAndWritesNothing() {
            assertThatThrownBy(() -> service.assignAdministrator(ADMIN, ALICE, REASON))
                    .isInstanceOf(ApprovalRequiredException.class);
            assertThat(adminRoles.findActive(ALICE)).isEmpty();
            assertNothingWritten();
        }

        @Test
        void bootstrapStillWorksBecauseItIsTheSeparateOperatorPath() {
            var fresh = new InMemoryAdminRoleRepository();
            var freshService = new ArchiveAdminService(fresh, audit, grants,
                    new InMemoryAdminIdentityRepository(), new InMemoryAdminAuditRepository());
            freshService.bootstrapFirstAdministratorWithIdentity(ALICE, "operator@bu.edu",
                    "first administrator", "https://issuer", "sub-alice", null, "alice");
            assertThat(fresh.findActive(ALICE)).isPresent();
        }
    }

    @Nested
    @DisplayName("Control 2: widening your own grant")
    class Widening {

        private final Instant soon = Instant.now().plus(10, ChronoUnit.DAYS);
        private final Instant later = Instant.now().plus(40, ChronoUnit.DAYS);

        @Test
        void selfAddingSubUnitsIsRefused() {
            AccessGrant own = seed(ADMIN, false, null);
            assertThatThrownBy(() -> service.modifyAccess(ADMIN, ADMIN, own.id(), null, true, REASON))
                    .isInstanceOf(SelfAdministrationException.class);
            assertThat(grants.findById(own.id()).orElseThrow().includeDescendants()).isFalse();
            assertNothingWritten();
        }

        @Test
        void selfExtendingExpiryIsRefused() {
            AccessGrant own = seed(ADMIN, false, soon);
            assertThatThrownBy(() -> service.modifyAccess(ADMIN, ADMIN, own.id(), later, false, REASON))
                    .isInstanceOf(SelfAdministrationException.class);
            assertThat(grants.findById(own.id()).orElseThrow().expiresAt()).isEqualTo(soon);
            assertNothingWritten();
        }

        @Test
        void selfRemovingExpiryIsRefused() {
            AccessGrant own = seed(ADMIN, false, soon);
            assertThatThrownBy(() -> service.modifyAccess(ADMIN, ADMIN, own.id(), null, false, REASON))
                    .isInstanceOf(SelfAdministrationException.class);
            assertNothingWritten();
        }

        @Test
        void selfNarrowingIsAllowed() {
            AccessGrant own = seed(ADMIN, true, later);
            service.modifyAccess(ADMIN, ADMIN, own.id(), soon, false, "narrowing my own access");
            AccessGrant after = grants.findById(own.id()).orElseThrow();
            assertThat(after.includeDescendants()).isFalse();
            assertThat(after.expiresAt()).isEqualTo(soon);
            assertThat(audit.events()).hasSize(1);
        }

        @Test
        void wideningSomeoneElsesGrantIsAllowedAndAuditsBeforeAndAfter() {
            AccessGrant theirs = seed(ALICE, false, soon);
            service.modifyAccess(ADMIN, ALICE, theirs.id(), null, true, "extended for the audit");

            assertThat(audit.events()).singleElement().satisfies(event -> {
                assertThat(event.action()).isEqualTo(AdminAuditAction.ACCESS_MODIFIED);
                assertThat(event.detail())
                        .contains("before={includeDescendants=false, expires=" + soon + "}")
                        .contains("after={includeDescendants=true, expires=never}")
                        .contains("extended for the audit");
            });
        }

        @Test
        void anExpiryInThePastIsRefused() {
            AccessGrant theirs = seed(ALICE, false, null);
            assertThatThrownBy(() -> service.modifyAccess(ADMIN, ALICE, theirs.id(),
                    Instant.now().minus(1, ChronoUnit.DAYS), false, REASON))
                    .isInstanceOf(IllegalArgumentException.class)
                    .isNotInstanceOf(ReasonRequiredException.class)
                    .hasMessageContaining("revoke");
            assertNothingWritten();
        }

        @Test
        void theWideningTable() {
            Instant t1 = Instant.parse("2030-01-01T00:00:00Z");
            Instant t2 = Instant.parse("2031-01-01T00:00:00Z");
            AccessGrant noDescNoExpiry = grant(false, null);
            AccessGrant descExpiring = grant(true, t1);

            assertThat(ArchiveAdminService.widens(noDescNoExpiry, null, true)).isTrue();
            assertThat(ArchiveAdminService.widens(noDescNoExpiry, null, false)).isFalse();
            assertThat(ArchiveAdminService.widens(noDescNoExpiry, t1, false))
                    .as("null -> date narrows").isFalse();
            assertThat(ArchiveAdminService.widens(descExpiring, t2, true)).as("later").isTrue();
            assertThat(ArchiveAdminService.widens(descExpiring, null, true)).as("to never").isTrue();
            assertThat(ArchiveAdminService.widens(descExpiring, t1, true)).as("unchanged").isFalse();
            assertThat(ArchiveAdminService.widens(descExpiring, t1, false)).isFalse();
            assertThat(ArchiveAdminService.widens(descExpiring,
                    t1.minus(1, ChronoUnit.DAYS), true)).as("earlier").isFalse();
        }

        private AccessGrant grant(boolean descendants, Instant expires) {
            return new AccessGrant(1, ALICE, GrantType.UNIT, "1234", descendants, null,
                    null, expires, null);
        }
    }

    @Nested
    @DisplayName("N1: the path identity owns the grant or link")
    class PathBinding {

        @Test
        void modifyingAnotherIdentitysGrantAnswersAsMissingAndChangesNothing() {
            AccessGrant bobs = seed(BOB, false, null);
            assertThatThrownBy(() -> service.modifyAccess(ADMIN, ALICE, bobs.id(), null, true, REASON))
                    .isInstanceOf(NoSuchElementException.class)
                    .hasMessage("no active grant " + bobs.id());
            assertThat(grants.findById(bobs.id()).orElseThrow().includeDescendants()).isFalse();
            assertNothingWritten();
        }

        @Test
        void revokingAnotherIdentitysGrantAnswersAsMissingAndChangesNothing() {
            AccessGrant bobs = seed(BOB, false, null);
            assertThatThrownBy(() -> service.revokeAccess(ADMIN, ALICE, bobs.id(), REASON))
                    .isInstanceOf(NoSuchElementException.class)
                    .hasMessage("no grant " + bobs.id());
            assertThat(grants.findById(bobs.id()).orElseThrow().revokedAt()).isNull();
            assertNothingWritten();
        }

        @Test
        void unlinkingAnotherIdentitysLinkAnswersAsMissingAndChangesNothing() {
            IdentityLink bobs = service.linkIdentity(ADMIN, "iss", "sub-bob", BOB, null, null, REASON);
            audit.clear();
            assertThatThrownBy(() -> service.unlinkIdentity(ADMIN, ALICE, bobs.id(), REASON))
                    .isInstanceOf(NoSuchElementException.class)
                    .hasMessage("no active identity link " + bobs.id());
            assertThat(identities.findLinksFor(BOB)).allMatch(IdentityLink::isActive);
            assertNothingWritten();
        }
    }

    @Nested
    @DisplayName("N2: unlink then link cannot move grants or ARCHIVE_ADMIN")
    class PrivilegedLink {

        @Test
        void linkingAnUnlinkedIdentityThatHoldsGrantsIsRefused() {
            identities.setActiveGrantCount(ALICE.value(), 1);
            assertThatThrownBy(() -> service.linkIdentity(ADMIN, "iss", "sub-new", ALICE,
                    null, null, REASON))
                    .isInstanceOf(ApprovalRequiredException.class);
            assertThat(identities.findLinksFor(ALICE)).isEmpty();
            assertNothingWritten();
        }

        @Test
        void linkingAnUnlinkedAdministratorIsRefused() {
            assertThatThrownBy(() -> service.linkIdentity(ADMIN, "iss", "sub-new", OTHER_ADMIN,
                    null, null, REASON))
                    .isInstanceOf(ApprovalRequiredException.class);
            assertNothingWritten();
        }

        @Test
        void linkingAFreshIdentityIsStillOneAdministratorsAct() {
            service.linkIdentity(ADMIN, "iss", "sub-new", ALICE, null, null, REASON);
            assertThat(identities.findLinksFor(ALICE)).hasSize(1);
            assertThat(audit.events()).hasSize(1);
        }
    }

    @Nested
    @DisplayName("Suspension")
    class Suspension {

        @Test
        void selfSuspensionIsRefused() {
            assertThatThrownBy(() -> service.setSuspended(ADMIN, ADMIN, true, REASON))
                    .isInstanceOf(SelfAdministrationException.class);
            assertThat(identities.isSuspended(ADMIN)).isFalse();
            assertNothingWritten();
        }

        @Test
        void suspendingAnotherAdministratorIsRefusedBecauseRestoreWouldNeedApproval() {
            assertThatThrownBy(() -> service.setSuspended(ADMIN, OTHER_ADMIN, true, REASON))
                    .isInstanceOf(ApprovalRequiredException.class);
            assertThat(identities.isSuspended(OTHER_ADMIN)).isFalse();
            assertNothingWritten();
        }

        @Test
        void restoringAnAdministratorIsRefusedUntilApprovalExists() {
            // Seeded: suspending an administrator through the service is refused too.
            identities.setSuspended(OTHER_ADMIN, true, "operator", "seeded suspension");

            assertThatThrownBy(() -> service.setSuspended(ADMIN, OTHER_ADMIN, false, REASON))
                    .isInstanceOf(ApprovalRequiredException.class);
            assertThat(identities.isSuspended(OTHER_ADMIN)).isTrue();
            assertNothingWritten();
        }

        @Test
        void restoringANonAdministratorIsOneAdministratorsAct() {
            service.setSuspended(ADMIN, ALICE, true, "suspended pending review");
            service.setSuspended(ADMIN, ALICE, false, "review found nothing wrong");
            assertThat(identities.isSuspended(ALICE)).isFalse();
            assertThat(audit.events()).hasSize(2);
        }
    }

    @Nested
    @DisplayName("QA N-161-1 / N-161-2: unlink and relink")
    class UnlinkAndRelink {

        @Test
        void selfUnlinkIsRefused() {
            IdentityLink own = identities.link("iss", "sub-admin", ADMIN, null, null, "seed");
            assertThatThrownBy(() -> service.unlinkIdentity(ADMIN, ADMIN, own.id(), REASON))
                    .isInstanceOf(SelfAdministrationException.class);
            assertThat(identities.findLinksFor(ADMIN)).allMatch(IdentityLink::isActive);
            assertNothingWritten();
        }

        @Test
        void relinkingAnAdministratorWithoutApprovalIsRefused() {
            IdentityLink theirs = identities.link("iss", "sub-b", OTHER_ADMIN, null, null, "seed");
            service.unlinkIdentity(ADMIN, OTHER_ADMIN, theirs.id(), "Cognito account compromised");
            audit.clear();

            // No grants, but ARCHIVE_ADMIN: the approval path is required. This
            // service has no approval store, so it is refused outright.
            assertThatThrownBy(() -> service.relinkIdentity(ADMIN, "iss", "ACCOMPLICE-SUB",
                    OTHER_ADMIN, null, null, REASON))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("ARCHIVE_ADMIN");
            assertThat(identities.findLinksFor(OTHER_ADMIN)).noneMatch(IdentityLink::isActive);
            assertNothingWritten();
        }

        @Test
        void relinkCannotChangeTheKualiPersonId() {
            IdentityLink bobs = identities.link("iss", "sub-bob", BOB, null, null, "seed");
            service.unlinkIdentity(ADMIN, BOB, bobs.id(), "Cognito pool replaced");
            audit.clear();

            assertThatThrownBy(() -> service.relinkIdentity(ADMIN, "iss", "sub-bob-2", BOB,
                    new edu.bu.archive.application.authorization.KualiPersonId("VICTIM-PI"),
                    null, REASON))
                    .isInstanceOf(ApprovalRequiredException.class)
                    .hasMessageContaining("Kuali person id");
            assertThat(identities.findLinksFor(BOB)).noneMatch(IdentityLink::isActive);
            assertNothingWritten();

            // The same principal (null) is still a single-administrator relink.
            service.relinkIdentity(ADMIN, "iss", "sub-bob-2", BOB, null, null, REASON);
            assertThat(identities.findLinksFor(BOB)).anyMatch(IdentityLink::isActive);
        }

        @Test
        void aBulkGrantWhoseOnlyEntryIsTheActorWritesNothing() {
            var report = service.grantAccessInBulk(ADMIN, List.of(ADMIN), unit("1234", false, null));
            assertThat(report.failed()).isEqualTo(1);
            assertThat(grants.findForGrantee(ADMIN)).isEmpty();
            assertNothingWritten();
        }
    }

    @Test
    @DisplayName("N4: a PARENT_AWARD specification keeps its award number")
    void parentAwardSpecificationKeepsTheAwardNumber() {
        AccessGrant granted = service.grantAccess(ADMIN, ALICE,
                new GrantSpecification(GrantType.PARENT_AWARD, null, false, null,
                        "100380-00001", null, REASON));
        assertThat(granted.awardNumber()).isEqualTo("100380-00001");
    }

    // ------------------------------------------------------------------
    // Decision D4: a reason on every mutating method, table-driven
    // ------------------------------------------------------------------

    static Stream<Arguments> badReasons() {
        return Stream.of(null, "", "   ", "Left dept", "x".repeat(250) + " " + "y".repeat(250),
                        "test", "N/A", "tbd todo", "see above")
                .map(Arguments::of);
    }

    /** Every mutating method, each fed the reason under test. */
    private List<Mutation> mutations() {
        AccessGrant alices = grants.create(ALICE, unit("1234", false, null), "seed");
        IdentityLink bobs = service.linkIdentity(ADMIN, "iss", "sub-bob", BOB, null, null, REASON);
        audit.clear();
        return List.of(
                new Mutation("grantAccess", r -> service.grantAccess(ADMIN, BOB,
                        new GrantSpecification(GrantType.UNIT, "5555", false, null, null, r))),
                new Mutation("grantAccessInBulk", r -> service.grantAccessInBulk(ADMIN,
                        List.of(BOB), new GrantSpecification(GrantType.UNIT, "5555", false, null, null, r))),
                new Mutation("modifyAccess", r -> service.modifyAccess(ADMIN, ALICE, alices.id(),
                        null, true, r)),
                new Mutation("revokeAccess", r -> service.revokeAccess(ADMIN, ALICE, alices.id(), r)),
                new Mutation("setSuspended(true)", r -> service.setSuspended(ADMIN, ALICE, true, r)),
                new Mutation("setSuspended(false)", r -> service.setSuspended(ADMIN, ALICE, false, r)),
                new Mutation("assignAdministrator", r -> service.assignAdministrator(ADMIN, ALICE, r)),
                new Mutation("revokeAdministrator", r -> service.revokeAdministrator(ADMIN, OTHER_ADMIN, r)),
                new Mutation("linkIdentity", r -> service.linkIdentity(ADMIN, "iss", "sub-alice",
                        ALICE, null, null, r)),
                new Mutation("unlinkIdentity", r -> service.unlinkIdentity(ADMIN, BOB, bobs.id(), r)),
                new Mutation("relinkIdentity", r -> service.relinkIdentity(ADMIN, "iss", "sub-bob-2",
                        BOB, null, null, r)),
                new Mutation("requestRelinkApproval", r -> service.requestRelinkApproval(ADMIN, BOB,
                        "iss", "sub-bob-2", r)),
                new Mutation("approveRelink", r -> service.approveRelink(ADMIN, 1L, r)),
                new Mutation("revokeRelinkApproval", r -> service.revokeRelinkApproval(ADMIN, 1L, r)));
    }

    record Mutation(String name, Consumer<String> call) { }

    @ParameterizedTest(name = "reason={0}")
    @MethodSource("badReasons")
    void everyMutatingMethodRefusesAnUnacceptableReasonAndWritesNothing(String reason) {
        for (Mutation mutation : mutations()) {
            int grantsBefore = grants.findForGrantee(ALICE).size() + grants.findForGrantee(BOB).size();
            boolean aliceRevoked = grants.findForGrantee(ALICE).get(0).revokedAt() != null;

            assertThatThrownBy(() -> mutation.call().accept(reason))
                    .as(mutation.name())
                    .isInstanceOf(ReasonRequiredException.class);

            assertThat(audit.events()).as(mutation.name() + " wrote an audit row").isEmpty();
            assertThat(grants.findForGrantee(ALICE).size() + grants.findForGrantee(BOB).size())
                    .as(mutation.name()).isEqualTo(grantsBefore);
            assertThat(grants.findForGrantee(ALICE).get(0).revokedAt() != null)
                    .as(mutation.name()).isEqualTo(aliceRevoked);
            assertThat(grants.findForGrantee(ALICE).get(0).includeDescendants())
                    .as(mutation.name()).isFalse();
            assertThat(identities.isSuspended(ALICE)).as(mutation.name()).isFalse();
            assertThat(adminRoles.findActive(OTHER_ADMIN)).as(mutation.name()).isPresent();
            assertThat(adminRoles.findActive(ALICE)).as(mutation.name()).isEmpty();
            assertThat(identities.findLinksFor(ALICE)).as(mutation.name()).isEmpty();
            assertThat(identities.findLinksFor(BOB)).as(mutation.name())
                    .hasSize(1).allMatch(IdentityLink::isActive);
        }
    }
}
