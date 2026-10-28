package edu.bu.archive.adapter.out.persistence.authorization;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Statement;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.DefaultTransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import edu.bu.archive.application.authorization.InstitutionalIdentifier;
import edu.bu.archive.application.authorization.admin.AdminAuditAction;
import edu.bu.archive.application.authorization.admin.AdminAuditSink;
import edu.bu.archive.application.authorization.admin.AdminRoleRepository;
import edu.bu.archive.application.authorization.admin.ArchiveAdminService;

/**
 * The complete authorization schema (V082, V083, V084) against a disposable
 * PostgreSQL with every committed migration applied, plus the transactional
 * contract that binds an administrative change to its audit row.
 *
 * <p>The repository and the audit sink are wrapped in a real
 * {@link TransactionInterceptor} driven by
 * {@link AnnotationTransactionAttributeSource}, so their
 * {@code @Transactional(propagation = MANDATORY)} declarations are actually in
 * force. Constructing them with {@code new} - as the concurrency test does,
 * where every call is wrapped explicitly - leaves the annotations inert, and a
 * MANDATORY assertion against a bare instance would pass for the wrong reason.
 *
 * <p>Synthetic identities only. No real administrator is bootstrapped and no
 * archive data access is granted.
 */
@Testcontainers
class AuthorizationSchemaAndAuditIntegrationTest {

    private static final Pattern MIGRATION_VERSION = Pattern.compile("^V(\\d+)__.*\\.sql$");

    private static final InstitutionalIdentifier ADMIN =
            new InstitutionalIdentifier("SYN-SCHEMA-ADMIN");
    private static final InstitutionalIdentifier SUBJECT =
            new InstitutionalIdentifier("SYN-SCHEMA-SUBJECT");

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg17")
                    .asCompatibleSubstituteFor("postgres"));

    private static JdbcClient jdbc;
    private static AdminRoleRepository adminRoles;
    private static AdminAuditSink auditSink;
    private static ArchiveAdminService service;
    /**
     * The same service with NO transactional proxy, so its own
     * {@code @Transactional} is inert. Used only to prove the ports still
     * refuse to run alone if that annotation were ever lost.
     */
    private static ArchiveAdminService serviceWithoutItsOwnTransaction;
    private static TransactionTemplate requiresNew;

    @BeforeAll
    static void applyEveryCommittedMigration() throws Exception {
        List<Path> migrations;
        try (Stream<Path> files = Files.list(locateMigrationsDirectory())) {
            migrations = files
                    .filter(p -> MIGRATION_VERSION.matcher(p.getFileName().toString()).matches())
                    .sorted(Comparator.comparingInt(
                            AuthorizationSchemaAndAuditIntegrationTest::migrationVersion))
                    .toList();
        }

        var dataSource = new SimpleDriverDataSource();
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
        PlatformTransactionManager txManager = new DataSourceTransactionManager(dataSource);

        adminRoles = transactional(
                new JdbcAdminRoleRepository(jdbc), AdminRoleRepository.class, txManager);
        auditSink = transactional(
                new JdbcAdminAuditSink(jdbc), AdminAuditSink.class, txManager);
        // Real JDBC adapters for the new collaborators too: this suite exists to
        // prove the declared transaction attributes hold against a real database.
        // The service is proxied too, not just the ports. Its own @Transactional
        // is what opens the transaction that the ports' MANDATORY propagation
        // then joins - exactly as Spring does in production. Constructing it
        // with `new` leaves those annotations inert and every write fails with
        // "no existing transaction", which is the ports working as designed.
        var adminIdentities = transactional(new JdbcAdminIdentityRepository(jdbc),
                edu.bu.archive.application.authorization.admin.AdminIdentityRepository.class, txManager);
        var grantRepository = transactional(new JdbcAccessGrantRepository(jdbc),
                edu.bu.archive.application.authorization.AccessGrantRepository.class, txManager);
        var auditReader = new JdbcAdminAuditRepository(jdbc);

        serviceWithoutItsOwnTransaction = new ArchiveAdminService(
                adminRoles, auditSink, grantRepository, adminIdentities, auditReader);

        service = transactionalClass(new ArchiveAdminService(
                adminRoles,
                auditSink,
                grantRepository, adminIdentities, auditReader),
                ArchiveAdminService.class, txManager);

        var definition = new DefaultTransactionDefinition();
        definition.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        requiresNew = new TransactionTemplate(txManager, definition);
    }

    /**
     * The same, for a concrete class with no port interface: CGLIB-proxied so
     * {@link ArchiveAdminService}'s own {@code @Transactional} methods really
     * start a transaction.
     */
    private static <T> T transactionalClass(
            T target, Class<T> type, PlatformTransactionManager txManager) {

        var factory = new ProxyFactory(target);
        factory.setProxyTargetClass(true);
        factory.addAdvice(new TransactionInterceptor(
                txManager, new AnnotationTransactionAttributeSource()));
        return type.cast(factory.getProxy());
    }

    /** Put the declared transaction attributes genuinely in force. */
    private static <T> T transactional(
            Object target, Class<T> port, PlatformTransactionManager txManager) {

        var factory = new ProxyFactory(target);
        factory.setInterfaces(port);
        factory.addAdvice(new TransactionInterceptor(
                txManager, new AnnotationTransactionAttributeSource()));
        return port.cast(factory.getProxy());
    }

    @BeforeEach
    void reset() {
        jdbc.sql("DELETE FROM authz.access_grant").update();
        jdbc.sql("DELETE FROM authz.identity_link").update();
        jdbc.sql("DELETE FROM authz.person_status").update();
        jdbc.sql("DELETE FROM authz.admin_role_member").update();
        clearAudit();
    }

    /**
     * TRUNCATE, not DELETE: the append-only trigger on authz.access_audit
     * rejects row deletes, which is exactly the property
     * {@code Schema.theAuditTableRejectsUpdatesAndDeletes} asserts.
     */
    private static void clearAudit() {
        jdbc.sql("TRUNCATE authz.access_audit").update();
    }

    @Nested
    class Schema {

        @Test
        void everyAuthorizationTableExists() {
            List<String> tables = jdbc.sql("""
                            SELECT table_name FROM information_schema.tables
                            WHERE table_schema = 'authz' ORDER BY table_name
                            """)
                    .query(String.class)
                    .list();

            assertThat(tables).containsExactlyInAnyOrder(
                    // V082
                    "identity_link", "person_status", "access_grant", "access_audit",
                    // V083
                    "kim_principal", "principal_crosswalk",
                    // V084
                    "admin_role_member");
        }

        @Test
        void adminRoleMemberHasTheExpectedColumns() {
            List<String> columns = jdbc.sql("""
                            SELECT column_name FROM information_schema.columns
                            WHERE table_schema = 'authz' AND table_name = 'admin_role_member'
                            ORDER BY column_name
                            """)
                    .query(String.class)
                    .list();

            assertThat(columns).containsExactlyInAnyOrder(
                    "admin_member_id", "institutional_identifier", "status",
                    "granted_by", "granted_at", "revoked_by", "revoked_at", "reason");
        }

        @Test
        void everyPartialUniquenessIndexTheResolverReliesOnExists() {
            List<String> indexes = jdbc.sql("""
                            SELECT indexname FROM pg_indexes WHERE schemaname = 'authz'
                            """)
                    .query(String.class)
                    .list();

            assertThat(indexes).contains(
                    "ux_admin_role_member_active",
                    "ux_identity_link_active_cognito",
                    "ux_principal_crosswalk_active_value",
                    "ux_principal_crosswalk_active_principal");
        }

        @Test
        void grantTypesAreConstrainedToTheFourSupportedCategories() {
            assertThatThrownBy(() -> requiresNew.executeWithoutResult(status -> jdbc.sql("""
                            INSERT INTO authz.access_grant
                                (institutional_identifier, grant_type, granted_by)
                            VALUES ('SYN-X', 'SUPERUSER', 'syn-admin')
                            """).update()))
                    .as("an unknown grant_type must be rejected by ck_access_grant_type")
                    .isInstanceOf(RuntimeException.class);
        }

        @Test
        void anIoGrantCarriesAnAccountValueAndNeverAUnit() {
            // D2: IO means the SAP account number. ck_access_grant_shape keeps a
            // UNIT and an IO grant from being conflated in one row.
            assertThatThrownBy(() -> requiresNew.executeWithoutResult(status -> jdbc.sql("""
                            INSERT INTO authz.access_grant
                                (institutional_identifier, grant_type, unit_number,
                                 io_value, granted_by)
                            VALUES ('SYN-X', 'IO', 'SYN-U-100', '9500312705', 'syn-admin')
                            """).update()))
                    .isInstanceOf(RuntimeException.class);
        }

        @Test
        void anIoGrantCannotSilentlyBecomeHierarchical() {
            // include_descendants is meaningful for UNIT only; on an IO grant it
            // would widen access to an undefined set.
            assertThatThrownBy(() -> requiresNew.executeWithoutResult(status -> jdbc.sql("""
                            INSERT INTO authz.access_grant
                                (institutional_identifier, grant_type, io_value,
                                 include_descendants, granted_by)
                            VALUES ('SYN-X', 'IO', '9500312705', TRUE, 'syn-admin')
                            """).update()))
                    .isInstanceOf(RuntimeException.class);
        }

        @Test
        void aSecondActiveIdentityLinkForOneCognitoIdentityIsImpossible() {
            String issuer = "https://cognito-idp.test.invalid/us-east-1_SYNTHETIC";
            requiresNew.executeWithoutResult(status -> jdbc.sql("""
                            INSERT INTO authz.identity_link
                                (cognito_issuer, cognito_subject, institutional_identifier,
                                 method, status, verified_by, verified_at)
                            VALUES (:iss, 'sub-dup', 'SYN-DUP-1', 'ADMIN_VERIFIED', 'ACTIVE',
                                    'syn-admin', now())
                            """).param("iss", issuer).update());

            assertThatThrownBy(() -> requiresNew.executeWithoutResult(status -> jdbc.sql("""
                            INSERT INTO authz.identity_link
                                (cognito_issuer, cognito_subject, institutional_identifier,
                                 method, status, verified_by, verified_at)
                            VALUES (:iss, 'sub-dup', 'SYN-DUP-2', 'ADMIN_VERIFIED', 'ACTIVE',
                                    'syn-admin', now())
                            """).param("iss", issuer).update()))
                    .as("ux_identity_link_active_cognito must prevent an ambiguous identity")
                    .isInstanceOf(RuntimeException.class);

            jdbc.sql("DELETE FROM authz.identity_link").update();
        }

        @Test
        void aCrosswalkRowCannotReferenceAnUnknownPrincipal() {
            assertThatThrownBy(() -> requiresNew.executeWithoutResult(status -> jdbc.sql("""
                            INSERT INTO authz.principal_crosswalk
                                (attribute_name, attribute_value, prncpl_id,
                                 evidence_ref, verified_by)
                            VALUES ('synthetic', 'syn-value', 'NO-SUCH-PRINCIPAL',
                                    'syn-evidence', 'syn-admin')
                            """).update()))
                    .as("the foreign key to authz.kim_principal is what keeps a rolodex "
                            + "contact id from being treated as a principal")
                    .isInstanceOf(RuntimeException.class);
        }

        @Test
        void theAuditTableRejectsUpdatesAndDeletes() {
            requiresNew.executeWithoutResult(status -> auditSink.record(
                    "syn-admin", AdminAuditAction.ACCESS_GRANTED, SUBJECT, "append only"));

            assertThatThrownBy(() -> requiresNew.executeWithoutResult(status ->
                    jdbc.sql("UPDATE authz.access_audit SET actor = 'tampered'").update()))
                    .as("tr_access_audit_append_only must reject an update")
                    .isInstanceOf(RuntimeException.class);

            assertThatThrownBy(() -> requiresNew.executeWithoutResult(status ->
                    jdbc.sql("DELETE FROM authz.access_audit").update()))
                    .as("tr_access_audit_append_only must reject a delete")
                    .isInstanceOf(RuntimeException.class);

            assertThat(auditActions()).hasSize(1);
        }

        @Test
        void theAuthzSchemaCarriesNoArchiveRecordData() {
            // authz holds the archive's own identity, grants and audit. A record
            // table appearing here could be reached by a record endpoint, which
            // this schema separation exists to prevent.
            List<String> tables = jdbc.sql("""
                            SELECT table_name FROM information_schema.tables
                            WHERE table_schema = 'authz'
                            """)
                    .query(String.class)
                    .list();

            assertThat(tables).allSatisfy(t -> assertThat(t)
                    .doesNotContain("award")
                    .doesNotContain("proposal")
                    .doesNotContain("negotiation")
                    .doesNotContain("subaward")
                    .doesNotContain("attachment"));
        }
    }

    @Nested
    class AuditTransactionality {

        private void seedAdministrator() {
            requiresNew.executeWithoutResult(status ->
                    service.bootstrapFirstAdministrator(
                            ADMIN, "operator@test.invalid", "schema fixture"));
            clearAudit();
        }

        @Test
        void anAdministrativeChangeAndItsAuditRowCommitTogether() {
            seedAdministrator();

            requiresNew.executeWithoutResult(status ->
                    service.assignAdministrator(ADMIN, SUBJECT, "granted in one transaction"));

            assertThat(service.isAdministrator(SUBJECT)).isTrue();
            assertThat(auditActions())
                    .containsExactly(AdminAuditAction.ADMIN_ROLE_ASSIGNED.name());
        }

        /**
         * The invariant that matters: a permission change must never survive
         * without its audit row. Rolling back after a successful assign must
         * discard both.
         */
        @Test
        void rollingBackDiscardsBothTheMembershipAndItsAuditRow() {
            seedAdministrator();

            assertThatThrownBy(() -> requiresNew.executeWithoutResult(status -> {
                service.assignAdministrator(ADMIN, SUBJECT, "about to fail");
                throw new IllegalStateException("simulated failure after the audit write");
            })).isInstanceOf(IllegalStateException.class);

            assertThat(service.isAdministrator(SUBJECT))
                    .as("the membership must not survive a rolled-back transaction")
                    .isFalse();
            assertThat(auditActions())
                    .as("the audit row must not survive either")
                    .isEmpty();
        }

        @Test
        void aRevokeAndItsAuditRowRollBackTogether() {
            seedAdministrator();
            requiresNew.executeWithoutResult(status ->
                    service.assignAdministrator(ADMIN, SUBJECT, "second administrator"));
            clearAudit();

            assertThatThrownBy(() -> requiresNew.executeWithoutResult(status -> {
                service.revokeAdministrator(ADMIN, SUBJECT, "about to fail");
                throw new IllegalStateException("simulated failure");
            })).isInstanceOf(IllegalStateException.class);

            assertThat(service.isAdministrator(SUBJECT))
                    .as("the revoke must be undone")
                    .isTrue();
            assertThat(auditActions()).isEmpty();
        }

        /**
         * MANDATORY propagation, proven against the real interceptor. An audit
         * write with no surrounding transaction must fail rather than quietly
         * commit on its own, which is what would let a change and its audit row
         * diverge.
         */
        @Test
        void theAuditSinkRefusesToWriteOutsideATransaction() {
            assertThatThrownBy(() -> auditSink.record(
                    "syn-admin", AdminAuditAction.ACCESS_GRANTED, SUBJECT, "no transaction"))
                    .isInstanceOf(IllegalTransactionStateException.class);

            assertThat(auditActions()).isEmpty();
        }

        /**
         * And the same for the membership write, so neither half of the pair can
         * be performed alone even if the service's own {@code @Transactional}
         * were ever lost.
         */
        @Test
        void theMembershipWriteRefusesToRunOutsideATransaction() {
            seedAdministrator();

            assertThatThrownBy(() ->
                    serviceWithoutItsOwnTransaction.assignAdministrator(ADMIN, SUBJECT, "no transaction"))
                    .isInstanceOf(IllegalTransactionStateException.class);

            assertThat(service.isAdministrator(SUBJECT)).isFalse();
            assertThat(auditActions()).isEmpty();
        }

        @Test
        void theLastAdministratorGuardLeavesNoAuditRowWhenItRefuses() {
            seedAdministrator();

            assertThatThrownBy(() -> requiresNew.executeWithoutResult(status ->
                    service.revokeAdministrator(ADMIN, ADMIN, "stepping down alone")))
                    .isInstanceOf(RuntimeException.class);

            assertThat(service.isAdministrator(ADMIN)).isTrue();
            assertThat(auditActions())
                    .as("a refused change must not be recorded as if it happened")
                    .isEmpty();
        }

        @Test
        void grantChangesAreAuditedWithActorSubjectAndTimestamp() {
            seedAdministrator();

            requiresNew.executeWithoutResult(status -> service.recordGrantChange(
                    ADMIN, AdminAuditAction.ACCESS_GRANTED, SUBJECT, "UNIT 2573180018"));

            var row = jdbc.sql("""
                            SELECT actor, action, institutional_identifier,
                                   occurred_at IS NOT NULL AS has_timestamp,
                                   detail::text AS detail
                            FROM authz.access_audit
                            """)
                    .query((rs, n) -> new Object[]{
                            rs.getString("actor"), rs.getString("action"),
                            rs.getString("institutional_identifier"),
                            rs.getBoolean("has_timestamp"), rs.getString("detail")})
                    .single();

            assertThat(row[0]).isEqualTo(ADMIN.value());
            assertThat(row[1]).isEqualTo(AdminAuditAction.ACCESS_GRANTED.name());
            assertThat(row[2]).isEqualTo(SUBJECT.value());
            assertThat(row[3]).as("timestamp recorded").isEqualTo(true);
            assertThat((String) row[4]).contains("2573180018");
        }

        @Test
        void auditDetailWithQuotesAndNewlinesStaysValidJsonb() {
            seedAdministrator();

            requiresNew.executeWithoutResult(status -> service.recordGrantChange(
                    ADMIN, AdminAuditAction.ACCESS_MODIFIED, SUBJECT,
                    "reason \"quoted\" with \\ backslash\nand a newline"));

            String note = jdbc.sql("SELECT detail->>'note' FROM authz.access_audit")
                    .query(String.class)
                    .single();

            assertThat(note)
                    .contains("\"quoted\"")
                    .contains("\\ backslash")
                    .contains("\nand a newline");
        }
    }

    private static List<String> auditActions() {
        return jdbc.sql("SELECT action FROM authz.access_audit ORDER BY audit_id")
                .query(String.class)
                .list();
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

    /**
     * The administrative write paths against real PostgreSQL. These exercise
     * SQL the unit tests cannot: RETURNING clauses, the ON CONFLICT upsert on
     * person_status, and - most importantly - that the CHECK constraints in
     * V082 and the Java validation agree about what a legal grant is.
     */
    @Nested
    class AdministrativeWrites {

        @BeforeEach
        void anAdministratorExists() {
            service.bootstrapFirstAdministrator(ADMIN, "operator@bu.edu", "integration test");
            clearAudit();
        }

        @Test
        void aGrantIsPersistedAndAuditedInOneTransaction() {
            var granted = service.grantAccess(ADMIN, SUBJECT,
                    new edu.bu.archive.application.authorization.admin.GrantSpecification(
                            edu.bu.archive.application.authorization.GrantType.UNIT,
                            "1234", true, null, null, "new analyst"));

            assertThat(granted.id()).isPositive();
            assertThat(jdbc.sql("SELECT COUNT(*) FROM authz.access_grant WHERE grant_id = :id")
                    .param("id", granted.id()).query(Integer.class).single()).isEqualTo(1);
            assertThat(jdbc.sql(
                            "SELECT action FROM authz.access_audit ORDER BY audit_id DESC LIMIT 1")
                    .query(String.class).single())
                    .isEqualTo(AdminAuditAction.ACCESS_GRANTED.name());
        }

        @Test
        void revokingSetsRevokedAtAndRevokedByRatherThanDeleting() {
            var granted = service.grantAccess(ADMIN, SUBJECT, central());

            service.revokeAccess(ADMIN, granted.id(), "left BU");

            var row = jdbc.sql("""
                            SELECT revoked_by, revoked_at IS NOT NULL AS revoked
                            FROM authz.access_grant WHERE grant_id = :id
                            """)
                    .param("id", granted.id())
                    .query((rs, n) -> rs.getString("revoked_by") + "/" + rs.getBoolean("revoked"))
                    .single();
            assertThat(row).isEqualTo(ADMIN.value() + "/true");
        }

        @Test
        void theDatabaseAndTheJavaValidationAgreeOnWhatAGrantMayBe() {
            // Java refuses an IO grant with descendants...
            assertThatThrownBy(() -> new edu.bu.archive.application.authorization.admin
                    .GrantSpecification(
                            edu.bu.archive.application.authorization.GrantType.IO,
                            null, true, "9500012345", null, null))
                    .isInstanceOf(IllegalArgumentException.class);

            // ...and so does ck_access_grant_shape, so the two cannot drift apart.
            assertThatThrownBy(() -> jdbc.sql("""
                            INSERT INTO authz.access_grant
                                (institutional_identifier, grant_type, io_value,
                                 include_descendants, granted_by)
                            VALUES ('SYN-X', 'IO', '9500012345', TRUE, 'SYN-ADMIN')
                            """).update())
                    .hasMessageContaining("ck_access_grant_shape");
        }

        @Test
        void suspensionUpsertsRatherThanFailingOnASecondCall() {
            service.setSuspended(ADMIN, SUBJECT, true, "under review");
            service.setSuspended(ADMIN, SUBJECT, false, "cleared");

            assertThat(jdbc.sql("""
                            SELECT suspended FROM authz.person_status
                            WHERE institutional_identifier = :id
                            """)
                    .param("id", SUBJECT.value())
                    .query(Boolean.class)
                    .single())
                    .isFalse();
            assertThat(jdbc.sql("SELECT COUNT(*) FROM authz.person_status")
                    .query(Integer.class).single()).isEqualTo(1);
        }

        @Test
        void aSecondActiveLinkForOneCognitoIdentityIsRefused() {
            service.linkIdentity(ADMIN, "https://issuer", "cognito-1", SUBJECT, null, null, null);

            assertThatThrownBy(() -> service.linkIdentity(
                    ADMIN, "https://issuer", "cognito-1", SUBJECT, null, null, null))
                    .isInstanceOf(IllegalStateException.class);
        }

        @Test
        void anAdministrativeChangeAndItsAuditRowRollBackTogether() {
            long before = auditCount();

            // Revoking a grant that does not exist fails after the role check,
            // so nothing - neither the change nor an audit row - may survive.
            assertThatThrownBy(() -> service.revokeAccess(ADMIN, 999_999L, "nope"))
                    .isInstanceOf(java.util.NoSuchElementException.class);

            assertThat(auditCount()).isEqualTo(before);
        }

        private long auditCount() {
            return jdbc.sql("SELECT COUNT(*) FROM authz.access_audit")
                    .query(Long.class).single();
        }

        private edu.bu.archive.application.authorization.admin.GrantSpecification central() {
            return new edu.bu.archive.application.authorization.admin.GrantSpecification(
                    edu.bu.archive.application.authorization.GrantType.CENTRAL,
                    null, false, null, null, null);
        }
    }
}
