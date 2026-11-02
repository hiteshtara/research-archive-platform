package edu.bu.archive.adapter.out.persistence.authorization;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Statement;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import edu.bu.archive.application.authorization.AccessGrantRepository;
import edu.bu.archive.application.authorization.InstitutionalIdentifier;
import edu.bu.archive.application.authorization.admin.AdminIdentityRepository;
import edu.bu.archive.application.authorization.admin.AdminRoleRepository;
import edu.bu.archive.application.authorization.admin.ArchiveAdminService;
import edu.bu.archive.application.authorization.admin.provisioning.GrantOutcome;
import edu.bu.archive.application.authorization.admin.provisioning.GrantProvisioningCommand;
import edu.bu.archive.application.authorization.admin.provisioning.GrantRequest;
import edu.bu.archive.application.authorization.admin.provisioning.EcsTaskMetadataRuntimeIdentity;
import edu.bu.archive.application.authorization.admin.provisioning.RuntimeIdentity;
import edu.bu.archive.application.authorization.admin.GrantSpecification;
import edu.bu.archive.application.authorization.GrantType;
import edu.bu.archive.application.authorization.admin.DuplicateActiveGrantException;
import java.util.Optional;
import java.util.concurrent.ExecutionException;

/**
 * The operator-only grant utility, against a real schema.
 *
 * <p>It writes nothing itself — every change goes through
 * {@link ArchiveAdminService}, which owns the transaction and the audit
 * row. These tests therefore check two things: that the utility refuses
 * what it should, and that when it does act, the grant and its audit row
 * appear together and nothing else does.
 */
@Testcontainers
@DisplayName("Operator-only grant provisioning")
class GrantProvisioningIntegrationTest {

    private static final Pattern MIGRATION_VERSION = Pattern.compile("^V(\\d+)__.*\\.sql$");
    private static final String ACCOUNT = "770203350335";
    private static final String ENVIRONMENT = "dev";
    private static final String ADMIN = "ARP-ADMN0001-00000001";
    private static final String GRANTEE = "ARP-GRANTEE1-00000001";
    private static final String OTHER_GRANTEE = "ARP-GRANTEE2-00000002";

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg17")
                    .asCompatibleSubstituteFor("postgres"));

    private static JdbcClient jdbc;
    private static PlatformTransactionManager txManager;
    private static ArchiveAdminService service;
    private static AdminRoleRepository adminRoles;
    private static AccessGrantRepository grants;

    @BeforeAll
    static void setUp() throws Exception {
        var dataSource = new SimpleDriverDataSource();
        dataSource.setDriverClass(org.postgresql.Driver.class);
        dataSource.setUrl(postgres.getJdbcUrl());
        dataSource.setUsername(postgres.getUsername());
        dataSource.setPassword(postgres.getPassword());

        List<Path> migrations;
        try (Stream<Path> files = Files.list(locateMigrations())) {
            migrations = files
                    .filter(p -> MIGRATION_VERSION.matcher(p.getFileName().toString()).matches())
                    .sorted(Comparator.comparingInt(GrantProvisioningIntegrationTest::version))
                    .toList();
        }
        try (Connection c = dataSource.getConnection(); Statement s = c.createStatement()) {
            for (Path m : migrations) {
                s.execute(Files.readString(m));
            }
        }

        jdbc = JdbcClient.create(dataSource);
        txManager = new DataSourceTransactionManager(dataSource);
        adminRoles = proxy(new JdbcAdminRoleRepository(jdbc), AdminRoleRepository.class);
        grants = proxy(new JdbcAccessGrantRepository(jdbc), AccessGrantRepository.class);
        AdminIdentityRepository identities =
                proxy(new JdbcAdminIdentityRepository(jdbc), AdminIdentityRepository.class);
        service = proxyClass(new ArchiveAdminService(
                adminRoles,
                proxy(new JdbcAdminAuditSink(jdbc),
                        edu.bu.archive.application.authorization.admin.AdminAuditSink.class),
                grants, identities, new JdbcAdminAuditRepository(jdbc)));
    }

    @BeforeEach
    void reset() {
        jdbc.sql("DELETE FROM authz.access_grant").update();
        jdbc.sql("DELETE FROM authz.identity_link").update();
        jdbc.sql("DELETE FROM authz.admin_role_member").update();
        jdbc.sql("TRUNCATE authz.access_audit").update();
        // A real administrator must exist; grants are issued BY one.
        service.bootstrapFirstAdministratorWithIdentity(
                new InstitutionalIdentifier(ADMIN), "operator@bu.edu", "test fixture",
                "https://cognito-idp.us-east-1.amazonaws.com/us-east-1_TEST",
                "00000000-0000-0000-0000-000000000001", null, "admin@bu.edu");
        jdbc.sql("TRUNCATE authz.access_audit").update();   // ignore bootstrap rows
    }

    @Nested
    @DisplayName("Writes nothing unless confirmed")
    class DryRun {

        @Test
        void aDryRunCreatesNoGrantAndNoAuditRow() {
            GrantOutcome outcome = command().run(req().dryRun(true).build());
            assertThat(outcome.result())
                    .as("problems: %s", outcome.problems())
                    .isEqualTo(GrantOutcome.Result.DRY_RUN_OK);
            assertThat(grantCount()).isZero();
            assertThat(auditCount()).isZero();
        }

        @Test
        void omittingConfirmIsTreatedAsADryRunNotAnError() {
            GrantOutcome outcome = command().run(req().build());
            assertThat(outcome.result()).isEqualTo(GrantOutcome.Result.DRY_RUN_OK);
            assertThat(outcome.message()).contains("--confirm was not given");
            assertThat(grantCount()).isZero();
        }
    }

    @Nested
    @DisplayName("A confirmed grant")
    class Confirmed {

        @Test
        void createsTheGrantAndItsAuditRow() {
            GrantOutcome outcome = command().run(req().confirmed(true).build());
            assertThat(outcome.result()).isEqualTo(GrantOutcome.Result.APPLIED);
            assertThat(outcome.grantId()).isNotNull();

            assertThat(grantCount()).isEqualTo(1);
            assertThat(jdbc.sql("""
                            SELECT grant_type FROM authz.access_grant
                            WHERE institutional_identifier = :g
                            """).param("g", GRANTEE).query(String.class).single())
                    .isEqualTo("UNIT");
            assertThat(auditActions()).containsExactly("ACCESS_GRANTED");
        }

        @Test
        void theAuditRowCarriesOperatorAndApprovalReference() {
            command().run(req().confirmed(true).build());
            String note = jdbc.sql("SELECT detail ->> 'note' FROM authz.access_audit")
                    .query(String.class).single();
            assertThat(note)
                    .contains("operator=operator@bu.edu")
                    .contains("approval=APPROVAL-2026-10-29")
                    .contains("first DEV grant");
        }

        @Test
        void aRevocationIsAudited() {
            GrantOutcome granted = command().run(req().confirmed(true).build());
            service.revokeAccess(new InstitutionalIdentifier(ADMIN), granted.grantId(), "no longer needed");
            assertThat(auditActions()).containsExactly("ACCESS_GRANTED", "ACCESS_REVOKED");
        }

        @Test
        void aModificationIsAudited() {
            GrantOutcome granted = command().run(req().confirmed(true).build());
            service.modifyAccess(new InstitutionalIdentifier(ADMIN), granted.grantId(), null, true, "widen to descendants");
            assertThat(auditActions()).containsExactly("ACCESS_GRANTED", "ACCESS_MODIFIED");
        }
    }

    @Nested
    @DisplayName("Refusals leave nothing behind")
    class Refusals {

        @Test
        void aStatedAccountThatDisagreesWithTheRuntimeIsRefused() {
            // The flag claims one account; the task is really in another.
            GrantOutcome outcome = command()
                    .run(req().account("999999999999").confirmed(true).build());
            assertThat(outcome.result()).isEqualTo(GrantOutcome.Result.REFUSED);
            assertThat(String.join(" ", outcome.problems())).contains("account mismatch");
            assertThat(grantCount()).isZero();
        }

        @Test
        void aStatedEnvironmentThatDisagreesWithTheClusterIsRefused() {
            assertRefused(req().environment("test").confirmed(true).build(), "environment");
        }

        @Test
        void confirmingWithNoVerifiableRuntimeIsRefused() {
            /*
             * The whole point of the runtime check. Outside ECS the account
             * cannot be verified, so a WRITE must refuse rather than trust
             * the flag - which is exactly what the first version did.
             */
            GrantOutcome outcome = command(RuntimeIdentity.UNKNOWN)
                    .run(req().confirmed(true).build());
            assertThat(outcome.result()).isEqualTo(GrantOutcome.Result.REFUSED);
            assertThat(String.join(" ", outcome.problems()))
                    .contains("cannot determine the AWS account from the runtime");
            assertThat(grantCount()).isZero();
            assertThat(auditCount()).isZero();
        }

        @Test
        void aDryRunWithNoVerifiableRuntimeIsAllowedButWarns() {
            GrantOutcome outcome = command(RuntimeIdentity.UNKNOWN)
                    .run(req().dryRun(true).build());
            assertThat(outcome.result()).isEqualTo(GrantOutcome.Result.DRY_RUN_OK);
            assertThat(String.join(" ", outcome.checks()))
                    .contains("runtime account unknown");
        }

        @Test
        void confirmingWithAVerifiedAccountButNoClusterIsRefused() {
            /*
             * The same hole the bootstrap utility had: the account verified,
             * so the outcome read as confirmed - and the environment check
             * and the production refusal were both skipped, because they sat
             * inside if (cluster.isPresent()). Shared RuntimeVerification
             * now refuses, so neither utility can drift back into it.
             */
            GrantOutcome outcome = command(runtime(ACCOUNT, null))
                    .run(req().confirmed(true).build());
            assertThat(outcome.result()).isEqualTo(GrantOutcome.Result.REFUSED);
            assertThat(String.join(" ", outcome.problems()))
                    .contains("cannot determine the ECS cluster from the runtime");
            assertThat(grantCount()).isZero();
            assertThat(auditCount()).isZero();
        }

        @Test
        void aDryRunWithAVerifiedAccountButNoClusterWarnsAndWritesNothing() {
            GrantOutcome outcome = command(runtime(ACCOUNT, null))
                    .run(req().dryRun(true).build());
            assertThat(outcome.result()).isEqualTo(GrantOutcome.Result.DRY_RUN_OK);
            assertThat(String.join(" ", outcome.checks())).contains("runtime cluster unknown");
            assertThat(grantCount()).isZero();
            assertThat(auditCount()).isZero();
        }

        @Test
        void aProductionClusterIsRefusedEvenForADryRun() {
            GrantOutcome outcome = command(runtime(ACCOUNT, "research-archive-platform-prod-api"))
                    .run(req().dryRun(true).build());
            assertThat(outcome.result()).isEqualTo(GrantOutcome.Result.REFUSED);
            assertThat(String.join(" ", outcome.problems()))
                    .contains("refuses to run against production");
            assertThat(grantCount()).isZero();
        }

        @Test
        void aProductionClusterIsRefusedWhateverTheFlagClaims() {
            GrantOutcome outcome = command(runtime(ACCOUNT, "research-archive-platform-prod-api"))
                    .run(req().confirmed(true).build());
            assertThat(outcome.result()).isEqualTo(GrantOutcome.Result.REFUSED);
            assertThat(String.join(" ", outcome.problems()))
                    .contains("refuses to run against production");
            assertThat(grantCount()).isZero();
        }

        @Test
        void anUnknownGrantTypeIsRefused() {
            assertRefused(req().type("SUPERUSER").confirmed(true).build(), "unknown grant type");
        }

        @Test
        void anEmailAsTheGranteeIsRefused() {
            assertRefused(req().grantee("mukadder@bu.edu").confirmed(true).build(),
                    "looks like an email address");
        }

        @Test
        void aUnitGrantWithoutAUnitNumberIsRefused() {
            assertRefused(req().type("UNIT").unit(null).confirmed(true).build(),
                    "UNIT grants need --unit-number");
        }

        @Test
        void anIoValueOnAUnitGrantIsRefused() {
            assertRefused(req().type("UNIT").io("9500234095").confirmed(true).build(),
                    "meaningless for a UNIT grant");
        }

        @Test
        void aScopeArgumentOnACentralGrantIsRefused() {
            assertRefused(req().type("CENTRAL").unit("1250000000").confirmed(true).build(),
                    "CENTRAL grants take no scope");
        }

        @Test
        void aMissingApprovalReferenceIsRefused() {
            assertRefused(req().approval(null).confirmed(true).build(), "--approval-reference");
        }

        @Test
        void anActorWithNoAdministratorMembershipIsRefused() {
            assertRefused(req().actor("ARP-N0TADMN1-00000001").confirmed(true).build(),
                    "holds no ACTIVE ARCHIVE_ADMIN membership");
        }

        @Test
        void anIdenticalActiveGrantIsRefusedRatherThanDuplicated() {
            command().run(req().confirmed(true).build());
            assertThat(grantCount()).isEqualTo(1);

            GrantOutcome second = command().run(req().confirmed(true).build());
            assertThat(second.result()).isEqualTo(GrantOutcome.Result.REFUSED);
            assertThat(String.join(" ", second.problems()))
                    .contains("identical active grant already exists");
            assertThat(grantCount()).as("still exactly one").isEqualTo(1);
            assertThat(auditActions()).containsExactly("ACCESS_GRANTED");
        }

        private void assertRefused(GrantRequest request, String expected) {
            GrantOutcome outcome = command().run(request);
            assertThat(outcome.result()).isEqualTo(GrantOutcome.Result.REFUSED);
            assertThat(String.join(" ", outcome.problems())).contains(expected);
            assertThat(grantCount()).as("a refusal writes no grant").isZero();
            assertThat(auditCount()).as("a refusal writes no audit row").isZero();
        }
    }

    @Test
    void theActiveScopeIndexExists() {
        /*
         * The guarantee below rests on V087 having been applied to this
         * container. Asserted directly, so a missing migration reads as
         * "the index is not there" rather than as a race that happened not
         * to collide on this run.
         */
        assertThat(jdbc.sql("""
                SELECT count(*) FROM pg_indexes
                 WHERE schemaname = 'authz' AND indexname = 'ux_access_grant_active_scope'
                """).query(Integer.class).single())
                .as("V087 must be applied for the concurrency guarantee to hold")
                .isEqualTo(1);
    }

    @Test
    void concurrentIdenticalRequestsProduceExactlyOneActiveGrant() throws Exception {
        /*
         * A GUARANTEE, not a characterisation. This test used to assert
         * active >= 1 and print a note when it saw more, because nothing
         * enforced the invariant: the duplicate check is a read followed by
         * a write with no lock, and authz.access_grant had only
         * ix_access_grant_grantee, a plain index. Four concurrent identical
         * requests produced FOUR active grants, and a later revocation -
         * which takes one grant_id - would have silently left the others in
         * force while the audit trail read as though access was removed.
         *
         * V087 added ux_access_grant_active_scope, so the database now holds
         * the invariant. Exactly one request wins; every loser is refused
         * with the same message the sequential duplicate check gives, and
         * writes neither a grant nor an audit row.
         */
        int attempts = 8;
        ExecutorService pool = Executors.newFixedThreadPool(attempts);
        try {
            List<Callable<GrantOutcome>> jobs = java.util.Collections.nCopies(attempts,
                    () -> command().run(req().confirmed(true).build()));

            List<GrantOutcome> outcomes = new java.util.ArrayList<>();
            for (Future<GrantOutcome> f : pool.invokeAll(jobs)) {
                // No exception is tolerated: a lost race must come back as a
                // refusal, not as a DataIntegrityViolationException.
                outcomes.add(f.get());
            }

            assertThat(outcomes)
                    .filteredOn(o -> o.result() == GrantOutcome.Result.APPLIED)
                    .as("exactly one of %d concurrent identical requests may win", attempts)
                    .hasSize(1);
            assertThat(outcomes)
                    .filteredOn(o -> o.result() == GrantOutcome.Result.REFUSED)
                    .as("every loser is refused")
                    .hasSize(attempts - 1);
            assertThat(outcomes.stream()
                    .filter(o -> o.result() == GrantOutcome.Result.REFUSED)
                    .allMatch(o -> String.join(" ", o.problems())
                            .contains("identical active grant already exists")))
                    .as("a lost race reads as the duplicate it is")
                    .isTrue();

            assertThat(activeGrantsFor(GRANTEE))
                    .as("the invariant: at most one active grant per grantee and scope")
                    .isEqualTo(1);
            assertThat(grantCount())
                    .as("no revoked or orphaned duplicate was left behind either")
                    .isEqualTo(1);
            assertThat(auditActions())
                    .as("one grant, one audit row - the losers rolled both back")
                    .containsExactly("ACCESS_GRANTED");
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void anUnreachableMetadataEndpointDoesNotInterruptTheCaller() {
        /*
         * EcsTaskMetadataRuntimeIdentity.fetch() caught every exception and
         * called Thread.currentThread().interrupt(), so a refused connection
         * - the normal outcome outside ECS - left the calling thread
         * interrupted and whatever JDBC work followed ran on it.
         */
        var unreachable = new EcsTaskMetadataRuntimeIdentity(
                "http://127.0.0.1:1/no-metadata-here");
        assertThat(unreachable.awsAccountId()).isEmpty();
        assertThat(unreachable.clusterName()).isEmpty();
        assertThat(Thread.currentThread().isInterrupted())
                .as("an unreachable metadata endpoint must not interrupt the caller")
                .isFalse();
    }

    @Test
    void aSecondGrantDifferingOnlyInIncludeDescendantsIsRefusedByTheIndex() {
        /*
         * The one single-threaded route into V087's constraint, and worth
         * having for its own sake.
         *
         * The application's pre-check calls two grants identical only when
         * includeDescendants agrees too (equalScope compares it). V087's
         * index deliberately leaves that flag out of the key, so that
         * modifyAccess can toggle it. The two therefore disagree by design
         * for exactly one request shape: same grantee, same unit, opposite
         * flag. The pre-check passes it and the index refuses it.
         *
         * That is the intended outcome - a grant including descendants
         * subsumes one that does not, so the right action is to modify the
         * existing grant rather than add a second. What matters is that the
         * operator is TOLD that, instead of being handed the
         * DataIntegrityViolationException this used to throw.
         */
        GrantOutcome first = command().run(req().confirmed(true).build());
        assertThat(first.result()).isEqualTo(GrantOutcome.Result.APPLIED);

        GrantOutcome second = command().run(req().descendants(true).confirmed(true).build());
        assertThat(second.result()).isEqualTo(GrantOutcome.Result.REFUSED);
        assertThat(String.join(" ", second.problems()))
                .contains("identical active grant already exists")
                .contains("the database constraint refused a duplicate");
        assertThat(second.message()).doesNotContain("Exception");

        assertThat(grantCount()).as("the refusal left no second row").isEqualTo(1);
        assertThat(auditActions())
                .as("and no audit row - the transaction rolled back")
                .containsExactly("ACCESS_GRANTED");

        // The documented way forward, which still works.
        service.modifyAccess(new InstitutionalIdentifier(ADMIN), first.grantId(),
                null, true, "widen to descendants");
        assertThat(auditActions()).containsExactly("ACCESS_GRANTED", "ACCESS_MODIFIED");
        assertThat(activeGrantsFor(GRANTEE)).isEqualTo(1);
    }

    @Test
    void theSequentialDuplicateCheckDoesHold() {
        command().run(req().confirmed(true).build());
        GrantOutcome second = command().run(req().confirmed(true).build());
        assertThat(second.result()).isEqualTo(GrantOutcome.Result.REFUSED);
        assertThat(activeGrantsFor(GRANTEE)).isEqualTo(1);
    }

    @Test
    void anAdministratorWithNoDataGrantStillHasNoGrants() {
        // Bootstrapping created the administrator in reset(). It must not
        // have given them any research-data access.
        assertThat(jdbc.sql("SELECT count(*) FROM authz.access_grant WHERE institutional_identifier = :g")
                .param("g", ADMIN).query(Integer.class).single())
                .isZero();
    }

    // ------------------------------------------------------------------
    // The service-level translation of V087's index violation.
    //
    // The CLI tests above go through GrantProvisioningCommand, which has its
    // own duplicate pre-check. These call ArchiveAdminService directly, which
    // is the path the administrative HTTP API takes - no pre-check, so the
    // index is the only thing standing in the way.
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("A duplicate grant through the service is a controlled failure")
    class ServiceLevelDuplicate {

        private GrantSpecification unitGrant() {
            return new GrantSpecification(GrantType.UNIT, "1242040000", false, null, null,
                    "service-level duplicate test");
        }

        @Test
        void aSequentialDuplicateThrowsDuplicateActiveGrantException() {
            service.grantAccess(actor(), grantee(), unitGrant());
            assertThat(grantCount()).isEqualTo(1);
            assertThat(auditActions()).containsExactly("ACCESS_GRANTED");

            assertThatThrownBy(() -> service.grantAccess(actor(), grantee(), unitGrant()))
                    .isInstanceOf(DuplicateActiveGrantException.class)
                    .hasMessageContaining("already exists");

            assertThat(grantCount()).as("the refusal wrote no second grant").isEqualTo(1);
            assertThat(auditActions())
                    .as("and no second audit row - audit.record is never reached")
                    .containsExactly("ACCESS_GRANTED");
        }

        @Test
        void concurrentDuplicatesLeaveExactlyOneGrantAndOneAuditRow() throws Exception {
            int attempts = 8;
            ExecutorService pool = Executors.newFixedThreadPool(attempts);
            try {
                List<Callable<Object>> jobs = java.util.Collections.nCopies(attempts,
                        () -> service.grantAccess(actor(), grantee(), unitGrant()));

                int applied = 0;
                int duplicates = 0;
                List<Throwable> unexpected = new java.util.ArrayList<>();
                for (Future<Object> f : pool.invokeAll(jobs)) {
                    try {
                        f.get();
                        applied++;
                    } catch (ExecutionException e) {
                        if (e.getCause() instanceof DuplicateActiveGrantException) {
                            duplicates++;
                        } else {
                            unexpected.add(e.getCause());
                        }
                    }
                }

                assertThat(unexpected)
                        .as("every loser must be a DuplicateActiveGrantException, "
                                + "not a raw DataIntegrityViolationException")
                        .isEmpty();
                assertThat(applied).as("exactly one winner").isEqualTo(1);
                assertThat(duplicates).isEqualTo(attempts - 1);

                assertThat(activeGrantsFor(GRANTEE)).isEqualTo(1);
                assertThat(grantCount()).isEqualTo(1);
                assertThat(auditActions())
                        .as("one grant, one audit row - the losers rolled back")
                        .containsExactly("ACCESS_GRANTED");
            } finally {
                pool.shutdownNow();
            }
        }

        /**
         * The administrator gate is untouched by the translation: it runs
         * before the insert, so a non-administrator is still refused for being
         * one, never for a duplicate.
         */
        @Test
        void theAdministratorCheckStillRunsFirst() {
            assertThatThrownBy(() -> service.grantAccess(
                    new InstitutionalIdentifier("ARP-N0TADMN1-00000001"), grantee(), unitGrant()))
                    .isNotInstanceOf(DuplicateActiveGrantException.class);
            assertThat(grantCount()).isZero();
            assertThat(auditCount()).isZero();
        }

        private InstitutionalIdentifier actor() {
            return new InstitutionalIdentifier(ADMIN);
        }

        private InstitutionalIdentifier grantee() {
            return new InstitutionalIdentifier(GRANTEE);
        }
    }

    // ------------------------------------------------------------------
    // Revocation: the lifecycle half the CLI could not reach before.
    //
    // While enforcement is OFF the administrative API 404s, so this is the
    // ONLY supported way to withdraw a grant. Hand-written SQL is not an
    // option: it would skip the ACCESS_REVOKED audit row.
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("Revocation by immutable grant id")
    class Revocation {

        private long liveGrant() {
            GrantOutcome created = command().run(req().confirmed(true).build());
            assertThat(created.result()).isEqualTo(GrantOutcome.Result.APPLIED);
            return created.grantId();
        }

        @Test
        void revocationUpdatesTheCorrectGrantAndAuditsAtomically() {
            long first = liveGrant();
            GrantOutcome second = command().run(
                    req().grantee(OTHER_GRANTEE).unit("1242030000").confirmed(true).build());
            assertThat(second.result()).isEqualTo(GrantOutcome.Result.APPLIED);
            assertThat(grantCount()).isEqualTo(2);

            GrantOutcome revoked = command().run(req().revokeOnly(first).confirmed(true).build());
            assertThat(revoked.result()).isEqualTo(GrantOutcome.Result.APPLIED);

            assertThat(revokedAt(first)).as("the named grant is revoked").isNotNull();
            assertThat(revokedAt(second.grantId())).as("the other grant is untouched").isNull();
            assertThat(grantCount()).as("nothing was deleted").isEqualTo(2);
            assertThat(auditActions())
                    .containsExactly("ACCESS_GRANTED", "ACCESS_GRANTED", "ACCESS_REVOKED");
        }

        @Test
        void aDryRunRevocationWritesNothing() {
            long id = liveGrant();
            GrantOutcome outcome = command().run(req().revokeOnly(id).dryRun(true).build());
            assertThat(outcome.result()).isEqualTo(GrantOutcome.Result.DRY_RUN_OK);
            assertThat(revokedAt(id)).as("still live").isNull();
            assertThat(auditActions()).containsExactly("ACCESS_GRANTED");
        }

        @Test
        void omittingConfirmIsADryRunNotAWrite() {
            long id = liveGrant();
            GrantOutcome outcome = command().run(req().revokeOnly(id).build());
            assertThat(outcome.result()).isEqualTo(GrantOutcome.Result.DRY_RUN_OK);
            assertThat(revokedAt(id)).isNull();
        }

        @Test
        void anUnknownGrantIdIsRefused() {
            GrantOutcome outcome = command().run(req().revokeOnly(999_999L).confirmed(true).build());
            assertThat(outcome.result()).isEqualTo(GrantOutcome.Result.REFUSED);
            assertThat(String.join(" ", outcome.problems())).contains("no grant has id 999999");
            assertThat(auditCount()).isZero();
        }

        @Test
        void aNonNumericOrNegativeGrantIdIsRefused() {
            for (String bad : List.of("abc", "-1", "0")) {
                GrantOutcome outcome = command().run(
                        req().revoke(bad).type(null).grantee(null).unit(null)
                                .confirmed(true).build());
                assertThat(outcome.result()).as("id %s", bad).isEqualTo(GrantOutcome.Result.REFUSED);
            }
            assertThat(auditCount()).isZero();
        }

        /** The misleading-audit case: a second revocation must not look like one. */
        @Test
        void repeatedRevocationIsRefusedAndWritesNoSecondAuditRow() {
            long id = liveGrant();
            assertThat(command().run(req().revokeOnly(id).confirmed(true).build()).result())
                    .isEqualTo(GrantOutcome.Result.APPLIED);
            assertThat(auditActions()).containsExactly("ACCESS_GRANTED", "ACCESS_REVOKED");

            GrantOutcome again = command().run(req().revokeOnly(id).confirmed(true).build());
            assertThat(again.result()).isEqualTo(GrantOutcome.Result.REFUSED);
            assertThat(String.join(" ", again.problems())).contains("was already revoked");
            assertThat(auditActions())
                    .as("exactly one ACCESS_REVOKED - a second would imply a withdrawal "
                            + "that did not happen")
                    .containsExactly("ACCESS_GRANTED", "ACCESS_REVOKED");
        }

        @Test
        void concurrentRevocationsOfOneGrantLeaveExactlyOneAuditRow() throws Exception {
            long id = liveGrant();
            int attempts = 8;
            ExecutorService pool = Executors.newFixedThreadPool(attempts);
            try {
                List<Callable<GrantOutcome>> jobs = java.util.Collections.nCopies(attempts,
                        () -> command().run(req().revokeOnly(id).confirmed(true).build()));
                int applied = 0;
                for (Future<GrantOutcome> f : pool.invokeAll(jobs)) {
                    try {
                        if (f.get().result() == GrantOutcome.Result.APPLIED) {
                            applied++;
                        }
                    } catch (ExecutionException ignored) {
                        // A loser may surface the repository's NoSuchElementException
                        // instead of a refusal; what matters is the row count below.
                    }
                }
                assertThat(applied).as("at most one revocation can win").isLessThanOrEqualTo(1);
                assertThat(revokedAt(id)).isNotNull();
                assertThat(grantCount()).as("nothing deleted").isEqualTo(1);
                assertThat(auditActions())
                        .as("exactly one ACCESS_REVOKED however many raced")
                        .containsExactly("ACCESS_GRANTED", "ACCESS_REVOKED");
            } finally {
                pool.shutdownNow();
            }
        }

        @Test
        void aRevokedScopeCanBeGrantedAgainWithoutViolatingV087() {
            long id = liveGrant();
            command().run(req().revokeOnly(id).confirmed(true).build());

            GrantOutcome replacement = command().run(req().confirmed(true).build());
            assertThat(replacement.result())
                    .as("V087 constrains UNREVOKED grants only")
                    .isEqualTo(GrantOutcome.Result.APPLIED);
            assertThat(replacement.grantId()).isNotEqualTo(id);
            assertThat(activeGrantsFor(GRANTEE)).isEqualTo(1);
            assertThat(grantCount()).as("the revoked row survives alongside the new one")
                    .isEqualTo(2);
            assertThat(auditActions())
                    .containsExactly("ACCESS_GRANTED", "ACCESS_REVOKED", "ACCESS_GRANTED");
        }

        @Test
        void aNonAdministratorCannotRevoke() {
            long id = liveGrant();
            GrantOutcome outcome = command().run(
                    req().revokeOnly(id).actor("ARP-N0TADMN1-00000001").confirmed(true).build());
            assertThat(outcome.result()).isEqualTo(GrantOutcome.Result.REFUSED);
            assertThat(String.join(" ", outcome.problems()))
                    .contains("holds no ACTIVE ARCHIVE_ADMIN membership");
            assertThat(revokedAt(id)).isNull();
        }

        @Test
        void revocationIsRefusedAgainstAProductionRuntime() {
            long id = liveGrant();
            GrantOutcome outcome = command(runtime(ACCOUNT,
                            "arn:aws:ecs:us-east-1:770203350335:cluster/"
                                    + "research-archive-platform-prod-api"))
                    .run(req().revokeOnly(id).confirmed(true).build());
            assertThat(outcome.result()).isEqualTo(GrantOutcome.Result.REFUSED);
            assertThat(String.join(" ", outcome.problems()))
                    .contains("refuses to run against production");
            assertThat(revokedAt(id)).isNull();
        }

        @Test
        void revokingCannotBeCombinedWithGranting() {
            long id = liveGrant();
            GrantOutcome outcome = command().run(
                    req().revoke(id).confirmed(true).build());   // keeps --type/--grantee
            assertThat(outcome.result()).isEqualTo(GrantOutcome.Result.REFUSED);
            assertThat(String.join(" ", outcome.problems()))
                    .contains("cannot be combined with");
            assertThat(revokedAt(id)).isNull();
        }
    }

    @Nested
    @DisplayName("Optional expiry (D-ADM-3)")
    class Expiry {

        @Test
        void anExpiryIsPersistedToAccessGrant() {
            GrantOutcome outcome = command()
                    .run(req().expiresAt("2027-06-30T00:00:00Z").confirmed(true).build());
            assertThat(outcome.result()).isEqualTo(GrantOutcome.Result.APPLIED);
            assertThat(jdbc.sql("SELECT expires_at FROM authz.access_grant WHERE grant_id = :id")
                    .param("id", outcome.grantId()).query(java.time.Instant.class).single())
                    .isEqualTo(java.time.Instant.parse("2027-06-30T00:00:00Z"));
        }

        @Test
        void noExpiryIsStillTheDefaultAndIsStatedOutLoud() {
            GrantOutcome outcome = command().run(req().confirmed(true).build());
            assertThat(outcome.result()).isEqualTo(GrantOutcome.Result.APPLIED);
            assertThat(jdbc.sql("SELECT expires_at FROM authz.access_grant WHERE grant_id = :id")
                    .param("id", outcome.grantId()).query(java.time.Instant.class).optional())
                    .isEmpty();
            assertThat(String.join(" ", outcome.checks())).contains("does not expire");
        }

        @Test
        void aMalformedExpiryIsRefused() {
            GrantOutcome outcome = command()
                    .run(req().expiresAt("30 June 2027").confirmed(true).build());
            assertThat(outcome.result()).isEqualTo(GrantOutcome.Result.REFUSED);
            assertThat(String.join(" ", outcome.problems())).contains("ISO-8601 instant");
            assertThat(grantCount()).isZero();
        }

        @Test
        void anExpiryInThePastIsRefused() {
            GrantOutcome outcome = command()
                    .run(req().expiresAt("2020-01-01T00:00:00Z").confirmed(true).build());
            assertThat(outcome.result()).isEqualTo(GrantOutcome.Result.REFUSED);
            assertThat(String.join(" ", outcome.problems())).contains("not in the future");
            assertThat(grantCount()).isZero();
        }

        /** The V087 interaction an operator must know about before choosing expiry. */
        @Test
        void anExpiredButUnrevokedGrantStillBlocksTheScope() {
            GrantOutcome first = command()
                    .run(req().expiresAt("2027-06-30T00:00:00Z").confirmed(true).build());
            assertThat(first.result()).isEqualTo(GrantOutcome.Result.APPLIED);

            // Force it past its expiry without revoking it.
            jdbc.sql("UPDATE authz.access_grant SET expires_at = now() - interval '1 day' "
                    + "WHERE grant_id = :id").param("id", first.grantId()).update();

            GrantOutcome replacement = command().run(req().confirmed(true).build());
            assertThat(replacement.result())
                    .as("V087's predicate is revoked_at IS NULL, not 'active now'")
                    .isEqualTo(GrantOutcome.Result.REFUSED);
            assertThat(String.join(" ", replacement.problems()))
                    .contains("identical active grant already exists");
            assertThat(activeGrantsFor(GRANTEE)).isEqualTo(1);
        }
    }

    // ---------------- helpers ----------------

    private static GrantProvisioningCommand command() {
        return command(runtime(ACCOUNT, "research-archive-platform-dev-api"));
    }

    private static GrantProvisioningCommand command(RuntimeIdentity runtime) {
        return new GrantProvisioningCommand(
                ACCOUNT, ENVIRONMENT, service, adminRoles, grants, runtime);
    }

    /** A runtime that reports a specific account and cluster. */
    private static RuntimeIdentity runtime(String account, String cluster) {
        return new RuntimeIdentity() {
            @Override
            public Optional<String> awsAccountId() {
                return Optional.ofNullable(account);
            }

            @Override
            public Optional<String> clusterName() {
                return Optional.ofNullable(cluster);
            }
        };
    }

    private static Req req() {
        return new Req();
    }

    private static final class Req {
        private String account = ACCOUNT;
        private String environment = ENVIRONMENT;
        private String grantee = GRANTEE;
        private String actor = ADMIN;
        private String type = "UNIT";
        private String unit = "1250000000";
        private boolean descendants;
        private String io;
        private String operator = "operator@bu.edu";
        private String reason = "first DEV grant";
        private String approval = "APPROVAL-2026-10-29";
        private boolean dryRun;
        private boolean confirmed;
        private String expiresAt;
        private String revokeGrantId;

        Req account(String v) { this.account = v; return this; }
        Req environment(String v) { this.environment = v; return this; }
        Req grantee(String v) { this.grantee = v; return this; }
        Req actor(String v) { this.actor = v; return this; }
        Req type(String v) { this.type = v; return this; }
        Req unit(String v) { this.unit = v; return this; }
        Req io(String v) { this.io = v; return this; }
        Req descendants(boolean v) { this.descendants = v; return this; }
        Req approval(String v) { this.approval = v; return this; }
        Req dryRun(boolean v) { this.dryRun = v; return this; }
        Req confirmed(boolean v) { this.confirmed = v; return this; }
        Req expiresAt(String v) { this.expiresAt = v; return this; }
        Req revoke(Object v) { this.revokeGrantId = v == null ? null : String.valueOf(v); return this; }
        /** A revocation carries no grant-shaped input at all. */
        Req revokeOnly(Object id) {
            this.revokeGrantId = String.valueOf(id);
            this.type = null; this.grantee = null; this.unit = null; this.io = null;
            this.descendants = false; this.expiresAt = null;
            return this;
        }

        GrantRequest build() {
            return new GrantRequest(account, environment, grantee, actor, type, unit,
                    descendants, io, operator, reason, approval, dryRun, confirmed,
                    expiresAt, revokeGrantId);
        }
    }

    private static java.time.Instant revokedAt(long grantId) {
        return jdbc.sql("SELECT revoked_at FROM authz.access_grant WHERE grant_id = :id")
                .param("id", grantId).query(java.time.Instant.class).optional().orElse(null);
    }

    private static int grantCount() {
        return jdbc.sql("SELECT count(*) FROM authz.access_grant").query(Integer.class).single();
    }

    private static int activeGrantsFor(String grantee) {
        return jdbc.sql("""
                        SELECT count(*) FROM authz.access_grant
                        WHERE institutional_identifier = :g AND revoked_at IS NULL
                        """).param("g", grantee).query(Integer.class).single();
    }

    private static int auditCount() {
        return jdbc.sql("SELECT count(*) FROM authz.access_audit").query(Integer.class).single();
    }

    private static List<String> auditActions() {
        return jdbc.sql("SELECT action FROM authz.access_audit ORDER BY audit_id")
                .query(String.class).list();
    }

    private static <T> T proxy(Object target, Class<T> port) {
        var factory = new ProxyFactory(target);
        factory.setInterfaces(port);
        factory.addAdvice(new TransactionInterceptor(
                txManager, new AnnotationTransactionAttributeSource()));
        return port.cast(factory.getProxy());
    }

    private static ArchiveAdminService proxyClass(ArchiveAdminService target) {
        var factory = new ProxyFactory(target);
        factory.setProxyTargetClass(true);
        factory.addAdvice(new TransactionInterceptor(
                txManager, new AnnotationTransactionAttributeSource()));
        return (ArchiveAdminService) factory.getProxy();
    }

    private static int version(Path path) {
        Matcher m = MIGRATION_VERSION.matcher(path.getFileName().toString());
        return m.matches() ? Integer.parseInt(m.group(1)) : Integer.MAX_VALUE;
    }

    private static Path locateMigrations() {
        Path c = Path.of("database", "migrations");
        return Files.isDirectory(c) ? c : Path.of("..", "database", "migrations");
    }
}
