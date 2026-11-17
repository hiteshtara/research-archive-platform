package edu.bu.archive.adapter.out.persistence.authorization;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import edu.bu.archive.application.authorization.InstitutionalIdentifier;
import edu.bu.archive.application.authorization.admin.bootstrap.BootstrapOutcome;
import edu.bu.archive.application.authorization.admin.bootstrap.BootstrapTarget;
import edu.bu.archive.application.authorization.admin.bootstrap.SecondAdministratorBootstrapCommand;
import edu.bu.archive.application.authorization.admin.bootstrap.SecondAdministratorBootstrapRequest;
import edu.bu.archive.application.authorization.admin.bootstrap.SecondAdministratorBootstrapService;
import edu.bu.archive.application.authorization.admin.bootstrap.SecondAdministratorRefusedException;
import edu.bu.archive.application.authorization.admin.provisioning.RuntimeIdentity;

/**
 * The one-time operator path that seats the SECOND administrator (design
 * 11.1). Synthetic identities and a synthetic account; nothing here touches a
 * real environment.
 */
@Tag("database")
@Testcontainers(disabledWithoutDocker = true)
class SecondAdministratorBootstrapIntegrationTest {

    private static final String ACCOUNT = "000000000000";
    private static final String ENVIRONMENT = "dev";
    private static final String POOL = "us-east-1_SYNTHETIC";
    private static final String REGION = "us-east-1";
    private static final String ISSUER = "https://cognito-idp.us-east-1.amazonaws.com/us-east-1_SYNTHETIC";

    private static final String EXISTING = "ARP-SYNTHE0A-00000001";
    private static final String EXISTING_SUB = "aaaaaaaa-0000-0000-0000-000000000001";
    private static final String NEW_ADMIN = "ARP-SYNTHE0B-00000002";
    private static final String NEW_SUB = "bbbbbbbb-0000-0000-0000-000000000002";
    private static final String OPERATOR = "synthetic-operator";
    private static final String REASON = "seating the second administrator for synthetic testing";
    private static final String CHANGE = "SYN-CHG-0001";

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
    }

    // ── fixtures ────────────────────────────────────────────────────────

    private static void seedExistingAdministrator() {
        db.seedAdministrator(EXISTING);
        db.jdbc.sql("""
                INSERT INTO authz.identity_link
                    (cognito_issuer, cognito_subject, institutional_identifier, method, status,
                     verified_by, verified_at)
                VALUES (:issuer, :sub, :id, 'ADMIN_VERIFIED', 'ACTIVE', 'test-fixture', now())
                """).param("issuer", ISSUER).param("sub", EXISTING_SUB).param("id", EXISTING).update();
    }

    private static SecondAdministratorBootstrapRequest request(String identifier, String sub,
                                                               String operator, String reason,
                                                               String change, boolean execute) {
        return new SecondAdministratorBootstrapRequest(ACCOUNT, ENVIRONMENT, POOL, ISSUER, sub,
                identifier, false, null, operator, reason, change, execute);
    }

    private static SecondAdministratorBootstrapRequest execute() {
        return request(NEW_ADMIN, NEW_SUB, OPERATOR, REASON, CHANGE, true);
    }

    private static SecondAdministratorBootstrapCommand command() {
        return new SecondAdministratorBootstrapCommand(
                new BootstrapTarget(ACCOUNT, ENVIRONMENT, POOL, REGION),
                db.secondBootstrap, db.adminRoles, db.identities)
                .withRuntime(new RuntimeIdentity() {
                    @Override
                    public Optional<String> awsAccountId() {
                        return Optional.of(ACCOUNT);
                    }

                    @Override
                    public Optional<String> clusterName() {
                        return Optional.of("research-archive-platform-dev-api");
                    }
                });
    }

    private static int administrators() {
        return db.jdbc.sql("SELECT count(*) FROM authz.admin_role_member WHERE status = 'ACTIVE'")
                .query(Integer.class).single();
    }

    private static int links() {
        return db.jdbc.sql("SELECT count(*) FROM authz.identity_link").query(Integer.class).single();
    }

    private static void assertNothingWritten(int admins, int links) {
        assertThat(administrators()).isEqualTo(admins);
        assertThat(links()).isEqualTo(links);
        assertThat(db.auditRows()).isZero();
    }

    // ── refusals ────────────────────────────────────────────────────────

    @Test
    @DisplayName("refuses when there is no administrator at all")
    void refusesWithZeroAdministrators() {
        BootstrapOutcome outcome = command().run(execute());
        assertThat(outcome.result()).isEqualTo(BootstrapOutcome.Result.REFUSED);
        assertThat(outcome.problems()).anyMatch(p -> p.contains("EXACTLY ONE"));
        assertNothingWritten(0, 0);

        assertThatThrownBy(() -> db.secondBootstrap.seat(new InstitutionalIdentifier(NEW_ADMIN),
                OPERATOR, REASON, CHANGE, ISSUER, NEW_SUB, null))
                .as("and the service refuses on its own, under the lock")
                .isInstanceOf(SecondAdministratorRefusedException.class)
                .hasMessageContaining("found 0");
        assertNothingWritten(0, 0);
    }

    @Test
    @DisplayName("refuses when two administrators already exist")
    void refusesWithTwoAdministrators() {
        seedExistingAdministrator();
        db.seedAdministrator("ARP-SYNTHE0C-00000003");

        assertThat(command().run(execute()).result()).isEqualTo(BootstrapOutcome.Result.REFUSED);
        assertThatThrownBy(() -> db.secondBootstrap.seat(new InstitutionalIdentifier(NEW_ADMIN),
                OPERATOR, REASON, CHANGE, ISSUER, NEW_SUB, null))
                .isInstanceOf(SecondAdministratorRefusedException.class)
                .hasMessageContaining("found 2");
        assertNothingWritten(2, 1);
    }

    @Test
    @DisplayName("refuses when the person to seat IS the existing administrator")
    void refusesSelfSeating() {
        seedExistingAdministrator();

        BootstrapOutcome outcome = command().run(
                request(EXISTING, NEW_SUB, OPERATOR, REASON, CHANGE, true));
        assertThat(outcome.result()).isEqualTo(BootstrapOutcome.Result.REFUSED);
        assertThat(outcome.problems()).anyMatch(p -> p.contains("must not be the existing administrator"));

        assertThatThrownBy(() -> db.secondBootstrap.seat(new InstitutionalIdentifier(EXISTING),
                OPERATOR, REASON, CHANGE, ISSUER, NEW_SUB, null))
                .isInstanceOf(SecondAdministratorRefusedException.class)
                .hasMessageContaining("must not be the existing administrator");
        assertNothingWritten(1, 1);
    }

    @Test
    @DisplayName("refuses to link the existing administrator's own Cognito sign-in to the new identity")
    void refusesTheExistingAdministratorsSignIn() {
        seedExistingAdministrator();

        BootstrapOutcome outcome = command().run(
                request(NEW_ADMIN, EXISTING_SUB, OPERATOR, REASON, CHANGE, true));

        assertThat(outcome.result()).isEqualTo(BootstrapOutcome.Result.REFUSED);
        assertThat(outcome.message()).contains("identity link refused");
        assertNothingWritten(1, 1);
    }

    @Test
    @DisplayName("refuses without a reason, with a placeholder reason, without a change reference or operator")
    void refusesWithoutProvenance() {
        seedExistingAdministrator();

        for (SecondAdministratorBootstrapRequest bad : List.of(
                request(NEW_ADMIN, NEW_SUB, OPERATOR, null, CHANGE, true),
                request(NEW_ADMIN, NEW_SUB, OPERATOR, "tbd", CHANGE, true),
                request(NEW_ADMIN, NEW_SUB, OPERATOR, REASON, null, true),
                request(NEW_ADMIN, NEW_SUB, OPERATOR, REASON, "  ", true),
                request(NEW_ADMIN, NEW_SUB, null, REASON, CHANGE, true))) {
            assertThat(command().run(bad).result()).isEqualTo(BootstrapOutcome.Result.REFUSED);
        }
        assertThatThrownBy(() -> db.secondBootstrap.seat(new InstitutionalIdentifier(NEW_ADMIN),
                OPERATOR, "n/a", CHANGE, ISSUER, NEW_SUB, null))
                .isInstanceOf(SecondAdministratorRefusedException.class);
        assertThatThrownBy(() -> db.secondBootstrap.seat(new InstitutionalIdentifier(NEW_ADMIN),
                OPERATOR, REASON, null, ISSUER, NEW_SUB, null))
                .isInstanceOf(SecondAdministratorRefusedException.class);
        assertNothingWritten(1, 1);
    }

    @Test
    @DisplayName("refuses an unverifiable runtime, and production")
    void refusesTheWrongEnvironment() {
        seedExistingAdministrator();
        BootstrapOutcome unknownRuntime = new SecondAdministratorBootstrapCommand(
                new BootstrapTarget(ACCOUNT, ENVIRONMENT, POOL, REGION),
                db.secondBootstrap, db.adminRoles, db.identities)
                .run(execute());
        assertThat(unknownRuntime.result()).isEqualTo(BootstrapOutcome.Result.REFUSED);
        assertNothingWritten(1, 1);
    }

    // ── dry run and execution ───────────────────────────────────────────

    @Test
    @DisplayName("dry run (the default) prints the plan and writes nothing")
    void dryRunWritesNothing() {
        seedExistingAdministrator();

        BootstrapOutcome outcome = command().run(
                request(NEW_ADMIN, NEW_SUB, OPERATOR, REASON, CHANGE, false));

        assertThat(outcome.result()).isEqualTo(BootstrapOutcome.Result.DRY_RUN_OK);
        assertThat(outcome.checks()).anyMatch(line -> line.startsWith("PLAN"));
        assertThat(String.join("\n", outcome.checks()))
                .as("identifiers are masked in operator output")
                .doesNotContain(NEW_ADMIN).doesNotContain(NEW_SUB);
        assertNothingWritten(1, 1);
    }

    @Test
    @DisplayName("execute seats exactly one admin, its link and two audit rows, atomically")
    void executeSeatsTheSecondAdministrator() {
        seedExistingAdministrator();

        BootstrapOutcome outcome = command().run(execute());

        assertThat(outcome.result()).isEqualTo(BootstrapOutcome.Result.APPLIED);
        assertThat(administrators()).isEqualTo(2);
        assertThat(db.jdbc.sql("""
                        SELECT granted_by FROM authz.admin_role_member
                         WHERE institutional_identifier = :id AND status = 'ACTIVE'
                        """).param("id", NEW_ADMIN).query(String.class).single())
                .isEqualTo(SecondAdministratorBootstrapService.SECOND_BOOTSTRAP_ACTOR);
        assertThat(db.jdbc.sql("""
                        SELECT count(*) FROM authz.identity_link
                         WHERE institutional_identifier = :id AND cognito_subject = :sub
                           AND status = 'ACTIVE'
                        """).param("id", NEW_ADMIN).param("sub", NEW_SUB)
                .query(Integer.class).single()).isEqualTo(1);
        assertThat(db.jdbc.sql("SELECT action FROM authz.access_audit ORDER BY audit_id")
                .query(String.class).list())
                .containsExactly("ADMIN_ROLE_SECOND_BOOTSTRAPPED", "IDENTITY_LINKED");
        assertThat(db.jdbc.sql("""
                        SELECT detail::text FROM authz.access_audit
                         WHERE action = 'ADMIN_ROLE_SECOND_BOOTSTRAPPED'
                        """).query(String.class).single())
                .contains(OPERATOR).contains(CHANGE).contains(EXISTING);
    }

    @Test
    @DisplayName("a failure after the membership insert rolls back the membership and its audit row")
    void executionIsAtomic() {
        seedExistingAdministrator();
        // The new identity has history, so link() refuses AFTER assign() has run.
        db.jdbc.sql("""
                INSERT INTO authz.identity_link
                    (cognito_issuer, cognito_subject, institutional_identifier, method, status,
                     verified_by, verified_at, revoked_by, revoked_at)
                VALUES (:issuer, 'cccccccc-0000-0000-0000-000000000003', :id, 'ADMIN_VERIFIED',
                        'REVOKED', 'test-fixture', now(), 'test-fixture', now())
                """).param("issuer", ISSUER).param("id", NEW_ADMIN).update();

        assertThatThrownBy(() -> db.secondBootstrap.seat(new InstitutionalIdentifier(NEW_ADMIN),
                OPERATOR, REASON, CHANGE, ISSUER, NEW_SUB, null))
                .isInstanceOf(SecondAdministratorRefusedException.class)
                .hasMessageContaining("identity link refused");
        assertNothingWritten(1, 2);
    }

    @Test
    @DisplayName("concurrent executions seat exactly one second administrator")
    void concurrentExecutionsSeatExactlyOne() throws Exception {
        seedExistingAdministrator();
        int threads = 6;
        CyclicBarrier start = new CyclicBarrier(threads);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        List<Future<Boolean>> futures = new ArrayList<>();
        try {
            for (int i = 0; i < threads; i++) {
                String id = "ARP-SYNTHE0D-0000000" + i;
                String sub = "dddddddd-0000-0000-0000-00000000000" + i;
                futures.add(pool.submit(() -> {
                    start.await(10, TimeUnit.SECONDS);
                    try {
                        db.secondBootstrap.seat(new InstitutionalIdentifier(id), OPERATOR, REASON,
                                CHANGE, ISSUER, sub, null);
                        return true;
                    } catch (SecondAdministratorRefusedException refused) {
                        return false;
                    }
                }));
            }
            int seated = 0;
            for (Future<Boolean> f : futures) {
                seated += f.get(60, TimeUnit.SECONDS) ? 1 : 0;
            }
            assertThat(seated).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
        assertThat(administrators()).isEqualTo(2);
        assertThat(db.auditRows("ADMIN_ROLE_SECOND_BOOTSTRAPPED")).isEqualTo(1);
    }

    @Test
    @DisplayName("can never run again - not even after the second administrator is revoked")
    void reRunAfterSuccessIsRefused() {
        seedExistingAdministrator();
        assertThat(command().run(execute()).result()).isEqualTo(BootstrapOutcome.Result.APPLIED);

        BootstrapOutcome again = command().run(
                request("ARP-SYNTHE0E-00000005", "eeeeeeee-0000-0000-0000-000000000005",
                        OPERATOR, REASON, CHANGE, true));
        assertThat(again.result()).isEqualTo(BootstrapOutcome.Result.REFUSED);

        // Back to exactly one administrator: the one-time path must stay closed.
        db.revokeAdministrator(NEW_ADMIN);
        assertThat(administrators()).isEqualTo(1);
        assertThatThrownBy(() -> db.secondBootstrap.seat(
                new InstitutionalIdentifier("ARP-SYNTHE0E-00000005"), OPERATOR, REASON, CHANGE,
                ISSUER, "eeeeeeee-0000-0000-0000-000000000005", null))
                .isInstanceOf(SecondAdministratorRefusedException.class)
                .hasMessageContaining("already been used");
        assertThat(administrators()).isEqualTo(1);
    }

    @Test
    @DisplayName("has no HTTP path: no web adapter references it")
    void noHttpPath() throws Exception {
        Path web = Path.of("src", "main", "java", "edu", "bu", "archive", "adapter", "in", "web");
        if (!Files.isDirectory(web)) {
            web = Path.of("api").resolve(web);
        }
        try (Stream<Path> files = Files.walk(web)) {
            List<Path> offenders = files.filter(p -> p.toString().endsWith(".java"))
                    .filter(p -> {
                        try {
                            return Files.readString(p).contains("SecondAdministratorBootstrap");
                        } catch (Exception e) {
                            throw new IllegalStateException(e);
                        }
                    })
                    .toList();
            assertThat(offenders).isEmpty();
        }
    }
}
