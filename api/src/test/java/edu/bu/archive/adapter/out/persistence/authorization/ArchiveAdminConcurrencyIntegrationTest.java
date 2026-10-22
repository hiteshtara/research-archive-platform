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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.DefaultTransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import edu.bu.archive.application.authorization.InstitutionalIdentifier;
import edu.bu.archive.application.authorization.admin.AdminRoleMember;
import edu.bu.archive.application.authorization.admin.LastArchiveAdministratorException;

/**
 * The last-administrator guard under real concurrency.
 *
 * <p>This is the test that justifies {@code SELECT ... FOR UPDATE} in
 * {@link JdbcAdminRoleRepository}. The rule - "never leave zero active
 * administrators" - is a condition over the set of rows, so no CHECK constraint
 * can enforce it. Without the row lock, two transactions each revoking one of
 * the final two administrators both observe a count of two, both proceed, and
 * the archive is left with no administrator and no way to appoint one through
 * the application.
 *
 * <p>Synthetic identities only. Nothing here grants archive data access, and no
 * real administrator is created.
 */
@Testcontainers
class ArchiveAdminConcurrencyIntegrationTest {

    private static final Pattern MIGRATION_VERSION = Pattern.compile("^V(\\d+)__.*\\.sql$");

    private static final InstitutionalIdentifier ADMIN_A =
            new InstitutionalIdentifier("SYN-ADMIN-A");
    private static final InstitutionalIdentifier ADMIN_B =
            new InstitutionalIdentifier("SYN-ADMIN-B");

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg17")
                    .asCompatibleSubstituteFor("postgres"));

    private static SimpleDriverDataSource dataSource;
    private static JdbcClient jdbc;
    private static JdbcAdminRoleRepository repository;
    private static TransactionTemplate requiresNew;

    @BeforeAll
    static void applyMigrations() throws Exception {
        List<Path> migrations;
        try (Stream<Path> files = Files.list(locateMigrationsDirectory())) {
            migrations = files
                    .filter(p -> MIGRATION_VERSION.matcher(p.getFileName().toString()).matches())
                    .sorted(Comparator.comparingInt(
                            ArchiveAdminConcurrencyIntegrationTest::migrationVersion))
                    .toList();
        }

        dataSource = new SimpleDriverDataSource();
        dataSource.setDriverClass(org.postgresql.Driver.class);
        dataSource.setUrl(postgres.getJdbcUrl());
        dataSource.setUsername(postgres.getUsername());
        dataSource.setPassword(postgres.getPassword());

        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            for (Path migration : migrations) {
                statement.execute(Files.readString(migration));
            }
        }

        jdbc = JdbcClient.create(dataSource);
        repository = new JdbcAdminRoleRepository(jdbc);

        var definition = new DefaultTransactionDefinition();
        definition.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        requiresNew = new TransactionTemplate(
                new DataSourceTransactionManager(dataSource), definition);
    }

    @BeforeEach
    void resetAndSeedTwoAdministrators() {
        jdbc.sql("DELETE FROM authz.admin_role_member").update();
        requiresNew.executeWithoutResult(status -> {
            repository.bootstrap(ADMIN_A, "operator@test.invalid", "concurrency fixture");
            repository.assign(ADMIN_B, ADMIN_A.value(), "second administrator");
        });
        assertThat(repository.countActive()).isEqualTo(2);
    }

    @Test
    void theTableExistsWithItsActiveUniquenessIndex() {
        Integer indexes = jdbc.sql("""
                        SELECT count(*) FROM pg_indexes
                        WHERE schemaname = 'authz'
                          AND tablename = 'admin_role_member'
                          AND indexname = 'ux_admin_role_member_active'
                        """)
                .query(Integer.class)
                .single();
        assertThat(indexes).isEqualTo(1);
    }

    @Test
    void aSecondActiveMembershipForOneIdentityIsImpossible() {
        assertThatThrownBy(() -> requiresNew.executeWithoutResult(status ->
                repository.assign(ADMIN_B, ADMIN_A.value(), "duplicate")))
                .isInstanceOf(RuntimeException.class);
        assertThat(repository.countActive()).isEqualTo(2);
    }

    @Test
    void bootstrapIsRefusedOnceAnAdministratorExists() {
        assertThatThrownBy(() -> requiresNew.executeWithoutResult(status ->
                repository.bootstrap(
                        new InstitutionalIdentifier("SYN-ADMIN-C"),
                        "operator@test.invalid", "replay")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("already exists");
        assertThat(repository.countActive()).isEqualTo(2);
    }

    @Test
    void revokingDownToOneAdministratorIsAllowed() {
        requiresNew.executeWithoutResult(status ->
                repository.revokeWithLastAdministratorGuard(
                        ADMIN_B, ADMIN_A.value(), "stepping down"));

        assertThat(repository.countActive()).isEqualTo(1);
        assertThat(repository.findActive(ADMIN_A)).isPresent();
        assertThat(repository.findActive(ADMIN_B)).isEmpty();
    }

    @Test
    void revokingTheLastAdministratorIsRefused() {
        requiresNew.executeWithoutResult(status ->
                repository.revokeWithLastAdministratorGuard(
                        ADMIN_B, ADMIN_A.value(), "stepping down"));

        assertThatThrownBy(() -> requiresNew.executeWithoutResult(status ->
                repository.revokeWithLastAdministratorGuard(
                        ADMIN_A, ADMIN_A.value(), "the last one")))
                .isInstanceOf(LastArchiveAdministratorException.class);

        assertThat(repository.countActive()).isEqualTo(1);
    }

    /**
     * Two threads, each revoking one of the final two administrators, released
     * simultaneously. Exactly one must succeed.
     *
     * <p>The assertion that matters is the last one: at least one administrator
     * survives. With the {@code FOR UPDATE} lock removed from the repository
     * this test fails with zero active administrators.
     */
    @Test
    void concurrentRevocationOfBothRemainingAdministratorsLeavesOne() throws Exception {
        CountDownLatch bothReady = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);

        try {
            Callable<Outcome> revokeA = revokeTask(ADMIN_A, ADMIN_B, bothReady, go);
            Callable<Outcome> revokeB = revokeTask(ADMIN_B, ADMIN_A, bothReady, go);

            Future<Outcome> first = pool.submit(revokeA);
            Future<Outcome> second = pool.submit(revokeB);

            assertThat(bothReady.await(20, TimeUnit.SECONDS))
                    .as("both threads reached the start line")
                    .isTrue();
            go.countDown();

            Outcome one = first.get(60, TimeUnit.SECONDS);
            Outcome other = second.get(60, TimeUnit.SECONDS);

            long succeeded = List.of(one, other).stream().filter(Outcome::succeeded).count();

            assertThat(succeeded)
                    .as("exactly one of two concurrent final revokes may succeed; "
                            + "outcomes were %s and %s", one, other)
                    .isEqualTo(1);

            assertThat(repository.countActive())
                    .as("an active administrator must always remain")
                    .isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
    }

    private Callable<Outcome> revokeTask(
            InstitutionalIdentifier subject, InstitutionalIdentifier actor,
            CountDownLatch ready, CountDownLatch go) {

        return () -> {
            ready.countDown();
            go.await(20, TimeUnit.SECONDS);
            try {
                requiresNew.executeWithoutResult(status ->
                        repository.revokeWithLastAdministratorGuard(
                                subject, actor.value(), "concurrent revoke"));
                return new Outcome(subject.value(), true, null);
            } catch (RuntimeException e) {
                Throwable root = rootCause(e);
                return new Outcome(subject.value(), false,
                        root.getClass().getSimpleName() + ": " + root.getMessage());
            }
        };
    }

    private static Throwable rootCause(Throwable t) {
        Throwable current = t;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        return current;
    }

    private record Outcome(String subject, boolean succeeded, String failure) {
        @Override
        public String toString() {
            return subject + (succeeded ? " succeeded" : " refused (" + failure + ")");
        }
    }

    private static int migrationVersion(Path path) {
        Matcher matcher = MIGRATION_VERSION.matcher(path.getFileName().toString());
        if (!matcher.matches()) {
            throw new IllegalStateException("not a migration: " + path);
        }
        return Integer.parseInt(matcher.group(1));
    }

    private static Path locateMigrationsDirectory() {
        Path candidate = Path.of("database", "migrations");
        if (Files.isDirectory(candidate)) {
            return candidate;
        }
        Path fromModule = Path.of("..", "database", "migrations");
        if (Files.isDirectory(fromModule)) {
            return fromModule;
        }
        throw new IllegalStateException("cannot locate database/migrations");
    }

    /** Guards against a future AdminRoleMember change breaking the bootstrap literal. */
    @Test
    void bootstrapActorIsRecordedAsTheBootstrapLiteral() {
        AdminRoleMember a = repository.findActive(ADMIN_A).orElseThrow();
        assertThat(a.grantedBy()).isEqualTo(AdminRoleMember.BOOTSTRAP_ACTOR);
        assertThat(a.reason()).contains("operator@test.invalid");
    }
}
