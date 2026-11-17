package edu.bu.archive.adapter.out.persistence.authorization;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Statement;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import javax.sql.DataSource;

import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.testcontainers.containers.PostgreSQLContainer;

import edu.bu.archive.application.authorization.admin.AdminAuditSink;
import edu.bu.archive.application.authorization.admin.AdminIdentityRepository;
import edu.bu.archive.application.authorization.admin.AdminRoleRepository;
import edu.bu.archive.application.authorization.admin.approval.PrivilegeApprovalRepository;
import edu.bu.archive.application.authorization.admin.approval.PrivilegeApprovalService;
import edu.bu.archive.application.authorization.admin.bootstrap.AdministratorSeatStore;
import edu.bu.archive.application.authorization.admin.bootstrap.SecondAdministratorBootstrapService;

/**
 * A disposable PostgreSQL carrying every released migration AND the
 * held-back ones (V089, V090), applied explicitly - the ETL runner never
 * sees {@code database/migrations-pending-approval/}, so a test that needs
 * V090 has to ask for it, exactly as {@code ActivityAuditSchemaIntegrationTest}
 * does for V089.
 *
 * <p>Also builds the real services behind Spring's transaction interceptor,
 * so {@code MANDATORY} propagation is genuinely enforced, not assumed.
 */
final class PrivilegeApprovalTestDatabase {

    private static final Pattern MIGRATION_VERSION = Pattern.compile("^V(\\d+)__.*\\.sql$");

    final DataSource dataSource;
    final JdbcClient jdbc;
    final PlatformTransactionManager tx;
    final AdminRoleRepository adminRoles;
    final AdminIdentityRepository identities;
    final AdminAuditSink audit;
    final PrivilegeApprovalRepository approvals;
    final PrivilegeApprovalService approvalService;
    final AdministratorSeatStore seats;
    final SecondAdministratorBootstrapService secondBootstrap;

    private PrivilegeApprovalTestDatabase(DataSource dataSource) {
        this.dataSource = dataSource;
        this.jdbc = JdbcClient.create(dataSource);
        this.tx = new DataSourceTransactionManager(dataSource);
        this.adminRoles = transactional(new JdbcAdminRoleRepository(jdbc), AdminRoleRepository.class);
        this.identities = transactional(new JdbcAdminIdentityRepository(jdbc),
                AdminIdentityRepository.class);
        this.audit = transactional(new JdbcAdminAuditSink(jdbc), AdminAuditSink.class);
        this.approvals = transactional(new JdbcPrivilegeApprovalRepository(jdbc),
                PrivilegeApprovalRepository.class);
        this.approvalService = transactionalClass(
                new PrivilegeApprovalService(approvals, adminRoles, identities, audit),
                PrivilegeApprovalService.class);
        this.seats = transactional(new JdbcAdministratorSeatStore(jdbc),
                AdministratorSeatStore.class);
        this.secondBootstrap = transactionalClass(
                new SecondAdministratorBootstrapService(seats, adminRoles, identities, audit),
                SecondAdministratorBootstrapService.class);
    }

    /** Apply released then held-back migrations, in version order. */
    static PrivilegeApprovalTestDatabase create(PostgreSQLContainer<?> postgres) throws Exception {
        var dataSource = new SimpleDriverDataSource(new org.postgresql.Driver(),
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            for (Path migration : migrations(locate("migrations"))) {
                statement.execute(Files.readString(migration));
            }
            for (Path migration : migrations(locate("migrations-pending-approval"))) {
                statement.execute(Files.readString(migration));
            }
        }
        return new PrivilegeApprovalTestDatabase(dataSource);
    }

    /** Every authz row a test can create, cleared between tests (TRUNCATE bypasses row triggers). */
    void reset() {
        jdbc.sql("""
                TRUNCATE authz.privilege_approval, authz.access_audit, authz.admin_role_member,
                         authz.person_status, authz.access_grant, authz.identity_link
                """).update();
    }

    void seedAdministrator(String identifier) {
        jdbc.sql("""
                INSERT INTO authz.admin_role_member (institutional_identifier, status, granted_by, reason)
                VALUES (:id, 'ACTIVE', 'test-fixture', 'synthetic administrator for tests')
                """).param("id", identifier).update();
    }

    void revokeAdministrator(String identifier) {
        jdbc.sql("""
                UPDATE authz.admin_role_member
                   SET status = 'REVOKED', revoked_by = 'test-fixture', revoked_at = CURRENT_TIMESTAMP
                 WHERE institutional_identifier = :id AND status = 'ACTIVE'
                """).param("id", identifier).update();
    }

    void suspend(String identifier) {
        jdbc.sql("""
                INSERT INTO authz.person_status (institutional_identifier, suspended, changed_by, changed_at, reason)
                VALUES (:id, TRUE, 'test-fixture', CURRENT_TIMESTAMP, 'synthetic suspension')
                ON CONFLICT (institutional_identifier) DO UPDATE SET suspended = TRUE
                """).param("id", identifier).update();
    }

    long seedGrant(String grantee, String unit) {
        return jdbc.sql("""
                INSERT INTO authz.access_grant (institutional_identifier, grant_type, unit_number, granted_by)
                VALUES (:grantee, 'UNIT', :unit, 'test-fixture')
                RETURNING grant_id
                """).param("grantee", grantee).param("unit", unit).query(Long.class).single();
    }

    int auditRows(String action) {
        return jdbc.sql("SELECT count(*) FROM authz.access_audit WHERE action = :a")
                .param("a", action).query(Integer.class).single();
    }

    int auditRows() {
        return jdbc.sql("SELECT count(*) FROM authz.access_audit").query(Integer.class).single();
    }

    String status(long approvalId) {
        return jdbc.sql("SELECT status FROM authz.privilege_approval WHERE approval_id = :id")
                .param("id", approvalId).query(String.class).single();
    }

    /**
     * Move an approval's whole timeline back by {@code hours}, bypassing the
     * immutability trigger for this one transaction. Superuser-only and
     * test-only: it is how "72 hours later" is simulated without waiting.
     */
    void backdate(long approvalId, int hours) {
        new org.springframework.transaction.support.TransactionTemplate(tx).executeWithoutResult(s -> {
            jdbc.sql("SET LOCAL session_replication_role = replica").update();
            jdbc.sql("""
                    UPDATE authz.privilege_approval
                       SET requested_at = requested_at - make_interval(hours => :h),
                           valid_until  = valid_until  - make_interval(hours => :h),
                           approved_at  = approved_at  - make_interval(hours => :h)
                     WHERE approval_id = :id
                    """).param("h", hours).param("id", approvalId).update();
        });
    }

    <T> T transactional(Object target, Class<T> port) {
        var factory = new ProxyFactory(target);
        factory.setInterfaces(port);
        factory.addAdvice(new TransactionInterceptor(tx, new AnnotationTransactionAttributeSource()));
        return port.cast(factory.getProxy());
    }

    <T> T transactionalClass(T target, Class<T> type) {
        var factory = new ProxyFactory(target);
        factory.setProxyTargetClass(true);
        factory.addAdvice(new TransactionInterceptor(tx, new AnnotationTransactionAttributeSource()));
        return type.cast(factory.getProxy());
    }

    static Path locate(String directory) {
        Path candidate = Path.of("database", directory);
        return Files.isDirectory(candidate) ? candidate : Path.of("..", "database", directory);
    }

    static List<Path> migrations(Path directory) throws Exception {
        if (!Files.isDirectory(directory)) {
            return List.of();
        }
        try (Stream<Path> files = Files.list(directory)) {
            return files
                    .filter(p -> MIGRATION_VERSION.matcher(p.getFileName().toString()).matches())
                    .sorted(Comparator.comparingInt(PrivilegeApprovalTestDatabase::version))
                    .toList();
        }
    }

    private static int version(Path path) {
        Matcher m = MIGRATION_VERSION.matcher(path.getFileName().toString());
        return m.matches() ? Integer.parseInt(m.group(1)) : Integer.MAX_VALUE;
    }
}
