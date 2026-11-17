package edu.bu.archive.adapter.out.persistence.authorization;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Statement;
import java.time.Clock;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
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
import edu.bu.archive.application.authorization.AccessOutcome;
import edu.bu.archive.application.authorization.AccessScopeResolver;
import edu.bu.archive.application.authorization.AuthorizationPolicy;
import edu.bu.archive.application.authorization.GrantType;
import edu.bu.archive.application.authorization.IdentityResolver;
import edu.bu.archive.application.authorization.InstitutionalIdentifier;
import edu.bu.archive.application.authorization.RecordAccessEvaluator;
import edu.bu.archive.application.authorization.RecordFacts;
import edu.bu.archive.application.authorization.ValidatedCognitoIdentity;
import edu.bu.archive.application.authorization.admin.AdminAuditSink;
import edu.bu.archive.application.authorization.admin.AdminIdentityRepository;
import edu.bu.archive.application.authorization.admin.AdminRoleRepository;
import edu.bu.archive.application.authorization.admin.ArchiveAdminService;
import edu.bu.archive.application.authorization.admin.GrantSpecification;

/**
 * Design section 7.6: what suspension and restoration do to grants, through
 * the REAL evaluator.
 *
 * <pre>
 *   ValidatedCognitoIdentity -> IdentityResolver -> AccessScopeResolver
 *     -> RecordAccessEvaluator -> decision on a synthetic Award
 * </pre>
 *
 * <p>Suspension overrides grants without touching them, so restoring is one
 * act that brings back exactly the grant that was there - the same
 * {@code grant_id}, no new row - and nothing else: a grant that expired or
 * was revoked while the identity was suspended stays dead.
 *
 * <p>Synthetic identities and records only; a throwaway database.
 */
@Testcontainers
@DisplayName("Suspension and restoration against the real evaluator (design 7.6)")
class GrantReactivationIntegrationTest {

    private static final Pattern MIGRATION_VERSION = Pattern.compile("^V(\\d+)__.*\\.sql$");
    private static final String ISSUER = "https://cognito-idp.us-east-1.amazonaws.com/us-east-1_REACT";
    private static final String ADMIN = "ARP-REACTADM-00000001";
    private static final String USER = "ARP-REACTUSR-00000001";
    private static final String UNIT = "7777000000";
    private static final String AWARD = "777001-00001";
    private static final Clock CLOCK = Clock.systemUTC();

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg17").asCompatibleSubstituteFor("postgres"));

    private static JdbcClient jdbc;
    private static PlatformTransactionManager txManager;
    private static ArchiveAdminService admin;
    private static AccessGrantRepository grants;
    private static JdbcRecordFactsRepository facts;
    private static IdentityResolver identityResolver;

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
                    .sorted(Comparator.comparingInt(GrantReactivationIntegrationTest::version))
                    .toList();
        }
        try (Connection c = dataSource.getConnection(); Statement s = c.createStatement()) {
            for (Path m : migrations) {
                s.execute(Files.readString(m));
            }
            s.execute("INSERT INTO archive.unit (unit_number, unit_name, parent_unit_number) "
                    + "VALUES ('" + UNIT + "', 'SYNTHETIC UNIT', NULL)");
            s.execute("""
                    INSERT INTO archive.award_version (
                        award_id, award_number, sequence_number, title, status_description,
                        lead_unit_number, lead_unit_name, is_primary_current, workflow_document_number
                    ) VALUES (97001, '%s', 1, 'Synthetic award', 'Active', '%s',
                              'SYNTHETIC UNIT', TRUE, 'DOC-REACT-1')
                    """.formatted(AWARD, UNIT));
        }
        jdbc = JdbcClient.create(dataSource);
        txManager = new DataSourceTransactionManager(dataSource);

        grants = proxy(new JdbcAccessGrantRepository(jdbc), AccessGrantRepository.class);
        var target = new ArchiveAdminService(
                proxy(new JdbcAdminRoleRepository(jdbc), AdminRoleRepository.class),
                proxy(new JdbcAdminAuditSink(jdbc), AdminAuditSink.class),
                grants,
                proxy(new JdbcAdminIdentityRepository(jdbc), AdminIdentityRepository.class),
                new JdbcAdminAuditRepository(jdbc));
        var factory = new ProxyFactory(target);
        factory.setProxyTargetClass(true);
        factory.addAdvice(new TransactionInterceptor(txManager, new AnnotationTransactionAttributeSource()));
        admin = (ArchiveAdminService) factory.getProxy();

        facts = new JdbcRecordFactsRepository(jdbc, new AwardAccountNumberIoResolver(jdbc));
        identityResolver = new IdentityResolver(new JdbcIdentityLinkRepository(jdbc));
    }

    @BeforeEach
    void reset() {
        jdbc.sql("DELETE FROM authz.access_grant").update();
        jdbc.sql("DELETE FROM authz.identity_link").update();
        jdbc.sql("DELETE FROM authz.admin_role_member").update();
        jdbc.sql("DELETE FROM authz.person_status").update();
        jdbc.sql("TRUNCATE authz.access_audit").update();

        admin.bootstrapFirstAdministratorWithIdentity(id(ADMIN), "operator@bu.edu",
                "reactivation fixture", ISSUER, "sub-react-admin", null, "admin@bu.edu");
        admin.linkIdentity(id(ADMIN), ISSUER, "sub-react-user", id(USER), null, "user@bu.edu",
                "verified in person for the fixture");
    }

    @Test
    @DisplayName("UNIT grant -> visible; suspend -> denied; restore -> visible, same grant_id, no new row")
    void restoreBringsBackTheSameGrantAndNothingNew() {
        long grantId = unitGrant(null);
        assertThat(canSee()).as("visible while granted").isTrue();

        admin.setSuspended(id(ADMIN), id(USER), true, "under review by compliance");
        assertThat(scope()).isInstanceOf(AccessOutcome.Denied.class);
        assertThat(((AccessOutcome.Denied) scope()).reason())
                .isEqualTo(AccessOutcome.DenialReason.SUSPENDED_IDENTITY);
        assertThat(canSee()).as("suspension takes effect on the next request").isFalse();

        admin.setSuspended(id(ADMIN), id(USER), false, "review closed with no finding");
        assertThat(canSee()).as("restored").isTrue();

        assertThat(jdbc.sql("SELECT grant_id FROM authz.access_grant WHERE institutional_identifier = :u")
                .param("u", USER).query(Long.class).list())
                .as("the same grant, and no new grant row")
                .containsExactly(grantId);
        assertThat(jdbc.sql("""
                        SELECT action FROM authz.access_audit
                         WHERE institutional_identifier = :u ORDER BY audit_id
                        """).param("u", USER).query(String.class).list())
                .containsExactly("IDENTITY_LINKED", "ACCESS_GRANTED",
                        "IDENTITY_SUSPENDED", "IDENTITY_RESTORED");
    }

    @Test
    @DisplayName("a grant that expired during suspension is not revived by restore")
    void anExpiredGrantIsNotRevived() {
        long grantId = unitGrant(CLOCK.instant().plusSeconds(3600));
        assertThat(canSee()).isTrue();

        admin.setSuspended(id(ADMIN), id(USER), true, "under review by compliance");
        // Time passes during the suspension: the expiry is now behind us.
        jdbc.sql("UPDATE authz.access_grant SET expires_at = now() - interval '1 day' WHERE grant_id = :id")
                .param("id", grantId).update();
        admin.setSuspended(id(ADMIN), id(USER), false, "review closed with no finding");

        assertThat(canSee()).as("restore must not extend an expiry").isFalse();
        assertThat(jdbc.sql("SELECT count(*) FROM authz.access_grant").query(Integer.class).single())
                .isEqualTo(1);
    }

    @Test
    @DisplayName("a grant revoked during suspension is not revived by restore")
    void aRevokedGrantIsNotRevived() {
        long grantId = unitGrant(null);
        admin.setSuspended(id(ADMIN), id(USER), true, "under review by compliance");
        admin.revokeAccess(id(ADMIN), id(USER), grantId, "access withdrawn after the review");
        admin.setSuspended(id(ADMIN), id(USER), false, "suspension lifted after revocation");

        assertThat(canSee()).as("restore must not un-revoke").isFalse();
        assertThat(jdbc.sql("SELECT revoked_at IS NOT NULL FROM authz.access_grant WHERE grant_id = :id")
                .param("id", grantId).query(Boolean.class).single()).isTrue();
    }

    // ---------------------------------------------------------------

    private long unitGrant(java.time.Instant expires) {
        return admin.grantAccess(id(ADMIN), id(USER),
                new GrantSpecification(GrantType.UNIT, UNIT, false, null, null, expires,
                        "department access for the fixture")).id();
    }

    private AccessOutcome scope() {
        return new AccessScopeResolver(
                grants, CLOCK, GrantReactivationIntegrationTest::policy,
                new JdbcContactRelationships(jdbc), new JdbcAwardHierarchy(jdbc))
                .resolve(identityResolver.resolve(new ValidatedCognitoIdentity(
                        ISSUER, "sub-react-user", "user", CLOCK.instant())));
    }

    private boolean canSee() {
        List<RecordFacts> family = facts.awardFamily(AWARD);
        String current = facts.currentAwardVersionKey(AWARD).orElseThrow();
        RecordFacts record = family.stream()
                .filter(f -> f.versionKey().equals(current)).findFirst().orElseThrow();
        return new RecordAccessEvaluator(policy().orElseThrow(), new JdbcUnitHierarchy(jdbc))
                .decide(scope(), record, family)
                .allowed();
    }

    private static Optional<AuthorizationPolicy> policy() {
        return Optional.of(new AuthorizationPolicy(
                AuthorizationPolicy.VersionScope.FAMILY_WIDE,
                AuthorizationPolicy.DepartmentMatch.LEAD_UNIT_WITH_DESCENDANTS,
                Set.of("PI", "COI", "KP", "MPI"),
                1,
                AuthorizationPolicy.ContactDerivation.EXPLICIT_GRANT));
    }

    private static InstitutionalIdentifier id(String value) {
        return new InstitutionalIdentifier(value);
    }

    private static <T> T proxy(Object target, Class<T> port) {
        var factory = new ProxyFactory(target);
        factory.setInterfaces(port);
        factory.addAdvice(new TransactionInterceptor(txManager, new AnnotationTransactionAttributeSource()));
        return port.cast(factory.getProxy());
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
