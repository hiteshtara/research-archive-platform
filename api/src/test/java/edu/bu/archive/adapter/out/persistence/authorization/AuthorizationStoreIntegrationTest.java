package edu.bu.archive.adapter.out.persistence.authorization;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
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
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import edu.bu.archive.application.authorization.AccessOutcome;
import edu.bu.archive.application.authorization.AccessScopeResolver;
import edu.bu.archive.application.authorization.GrantType;
import edu.bu.archive.application.authorization.IdentityResolution;
import edu.bu.archive.application.authorization.IdentityResolver;
import edu.bu.archive.application.authorization.InstitutionalIdentifier;
import edu.bu.archive.application.authorization.ValidatedCognitoIdentity;

/**
 * The V082 authorization store against a disposable Postgres with every
 * committed migration applied. Synthetic rows only.
 */
@Testcontainers
class AuthorizationStoreIntegrationTest {

    private static final Pattern MIGRATION_VERSION = Pattern.compile("^V(\\d+)__.*\\.sql$");
    private static final String ISSUER = "https://cognito-idp.test.invalid/us-east-1_SYNTHETIC";

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg17").asCompatibleSubstituteFor("postgres"));

    private static JdbcClient jdbc;

    @BeforeAll
    static void applyMigrationsAndSeed() throws Exception {
        List<Path> migrations;
        try (Stream<Path> files = Files.list(locateMigrationsDirectory())) {
            migrations = files.filter(p -> MIGRATION_VERSION.matcher(p.getFileName().toString()).matches())
                    .sorted(Comparator.comparingInt(AuthorizationStoreIntegrationTest::migrationVersion))
                    .toList();
        }
        var dataSource = new SimpleDriverDataSource();
        dataSource.setDriverClass(org.postgresql.Driver.class);
        dataSource.setUrl(postgres.getJdbcUrl());
        dataSource.setUsername(postgres.getUsername());
        dataSource.setPassword(postgres.getPassword());
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            for (Path migration : migrations) {
                statement.execute(Files.readString(migration));
            }
            statement.execute("""
                    INSERT INTO archive.unit (unit_number, unit_name, parent_unit_number) VALUES
                      ('SYN-U-001', 'Synthetic root', NULL),
                      ('SYN-U-100', 'Synthetic department', 'SYN-U-001'),
                      ('SYN-U-110', 'Synthetic sub-unit', 'SYN-U-100'),
                      ('SYN-U-CYC-A', 'Synthetic cycle A', 'SYN-U-CYC-B'),
                      ('SYN-U-CYC-B', 'Synthetic cycle B', 'SYN-U-CYC-A')
                    """);
            statement.execute("""
                    INSERT INTO authz.identity_link (cognito_issuer, cognito_subject, institutional_identifier,
                        kuali_person_id, login_name, method, status, verified_by, verified_at, revoked_by, revoked_at)
                    VALUES
                      ('%1$s', 'sub-dept', 'SYN-INST-1002', 'SYNP-1002', 'syn-dept', 'ADMIN_VERIFIED', 'ACTIVE',
                       'syn-admin', now(), NULL, NULL),
                      ('%1$s', 'sub-old', 'SYN-INST-1010', 'SYNP-1010', 'syn-shared', 'ADMIN_VERIFIED', 'REVOKED',
                       'syn-admin', now(), 'syn-admin', now()),
                      ('%1$s', 'sub-new', 'SYN-INST-1011', 'SYNP-1011', 'syn-shared', 'ADMIN_VERIFIED', 'ACTIVE',
                       'syn-admin', now(), NULL, NULL),
                      ('%1$s', 'sub-susp', 'SYN-INST-1012', 'SYNP-1012', 'syn-susp', 'ADMIN_VERIFIED', 'ACTIVE',
                       'syn-admin', now(), NULL, NULL)
                    """.formatted(ISSUER));
            statement.execute("""
                    INSERT INTO authz.person_status (institutional_identifier, suspended, changed_by, changed_at)
                    VALUES ('SYN-INST-1012', TRUE, 'syn-admin', now())
                    """);
            statement.execute("""
                    INSERT INTO authz.access_grant (institutional_identifier, grant_type, unit_number,
                        include_descendants, io_value, granted_by, revoked_by, revoked_at, expires_at) VALUES
                      ('SYN-INST-1002', 'UNIT', 'SYN-U-100', TRUE, NULL, 'syn-admin', NULL, NULL, NULL),
                      ('SYN-INST-1002', 'IO', NULL, FALSE, 'SYN-IO-0001', 'syn-admin', 'syn-admin', now(), NULL),
                      ('SYN-INST-1002', 'CENTRAL', NULL, FALSE, NULL, 'syn-admin', NULL, NULL, now() - interval '1 day'),
                      ('SYN-INST-1010', 'CENTRAL', NULL, FALSE, NULL, 'syn-admin', NULL, NULL, NULL),
                      ('SYN-INST-1012', 'CENTRAL', NULL, FALSE, NULL, 'syn-admin', NULL, NULL, NULL)
                    """);
        }
        jdbc = JdbcClient.create(dataSource);
    }

    private static IdentityResolution resolve(String subject) {
        return new IdentityResolver(new JdbcIdentityLinkRepository(jdbc))
                .resolve(new ValidatedCognitoIdentity(ISSUER, subject));
    }

    private static AccessOutcome outcome(String subject) {
        return new AccessScopeResolver(new JdbcAccessGrantRepository(jdbc), java.time.Clock.systemUTC())
                .resolve(resolve(subject));
    }

    @Test
    void anActiveLinkResolvesAndOnlyActiveUnexpiredGrantsCount() {
        assertThat(resolve("sub-dept")).isInstanceOf(IdentityResolution.Mapped.class);
        var scoped = (AccessOutcome.Scoped) outcome("sub-dept");
        assertThat(scoped.scope().central()).isFalse();
        assertThat(scoped.scope().ioValues()).isEmpty();
        assertThat(scoped.scope().units()).hasSize(1);
    }

    @Test
    void unknownSubjectsAreNotProvisionedAndRevokedLinksAreRevoked() {
        assertThat(resolve("sub-unknown")).isInstanceOf(IdentityResolution.NotProvisioned.class);
        assertThat(resolve("sub-old")).isInstanceOf(IdentityResolution.Revoked.class);
    }

    @Test
    void aReassignedLoginNameDoesNotCarryTheOldHoldersCentralGrant() {
        assertThat(outcome("sub-new")).isEqualTo(
                new AccessOutcome.NotProvisioned(AccessOutcome.NotProvisionedReason.NO_ACTIVE_GRANTS));
    }

    @Test
    void suspensionDeniesDespiteAnActiveCentralGrant() {
        assertThat(outcome("sub-susp")).isEqualTo(
                new AccessOutcome.Denied(AccessOutcome.DenialReason.SUSPENDED_IDENTITY));
    }

    @Test
    void onlyOneActiveLinkPerCognitoIdentityCanBeWritten() {
        assertThatThrownBy(() -> jdbc.sql("""
                        INSERT INTO authz.identity_link (cognito_issuer, cognito_subject, institutional_identifier,
                            method, status, verified_by, verified_at)
                        VALUES (:issuer, 'sub-dept', 'SYN-INST-9999', 'ADMIN_VERIFIED', 'ACTIVE', 'syn-admin', now())
                        """).param("issuer", ISSUER).update())
                .hasMessageContaining("ux_identity_link_active_cognito");
    }

    @Test
    void grantShapesAreEnforced() {
        assertThatThrownBy(() -> jdbc.sql("""
                INSERT INTO authz.access_grant (institutional_identifier, grant_type, granted_by)
                VALUES ('SYN-INST-1002', 'UNIT', 'syn-admin')
                """).update()).hasMessageContaining("ck_access_grant_shape");
        assertThatThrownBy(() -> jdbc.sql("""
                INSERT INTO authz.access_grant (institutional_identifier, grant_type, io_value, granted_by)
                VALUES ('SYN-INST-1002', 'CENTRAL', 'SYN-IO-1', 'syn-admin')
                """).update()).hasMessageContaining("ck_access_grant_shape");
    }

    @Test
    void theAuditTrailIsAppendOnly() {
        jdbc.sql("""
                INSERT INTO authz.access_audit (actor, action, institutional_identifier, detail)
                VALUES ('syn-admin', 'GRANT_ADDED', 'SYN-INST-1002', '{"type":"UNIT"}'::jsonb)
                """).update();
        assertThatThrownBy(() -> jdbc.sql("UPDATE authz.access_audit SET actor = 'x'").update())
                .hasMessageContaining("append-only");
        assertThatThrownBy(() -> jdbc.sql("DELETE FROM authz.access_audit").update())
                .hasMessageContaining("append-only");
    }

    @Test
    void unitHierarchyFindsDescendantsAndSurvivesCycles() {
        var hierarchy = new JdbcUnitHierarchy(jdbc);
        assertThat(hierarchy.isSameOrDescendant("SYN-U-110", "SYN-U-100")).isTrue();
        assertThat(hierarchy.isSameOrDescendant("SYN-U-100", "SYN-U-110")).isFalse();
        assertThat(hierarchy.isSameOrDescendant("SYN-U-UNKNOWN", "SYN-U-100")).isFalse();
        assertThat(hierarchy.isSameOrDescendant("SYN-U-CYC-A", "SYN-U-100")).isFalse();
    }

    @Test
    void grantsAreReadOnlyForTheirOwnGrantee() {
        assertThat(new JdbcAccessGrantRepository(jdbc).findForGrantee(new InstitutionalIdentifier("SYN-INST-1002")))
                .extracting(grant -> grant.type())
                .containsExactlyInAnyOrder(GrantType.UNIT, GrantType.IO, GrantType.CENTRAL);
    }

    private static int migrationVersion(Path path) {
        Matcher matcher = MIGRATION_VERSION.matcher(path.getFileName().toString());
        if (!matcher.matches()) {
            throw new IllegalStateException("Not a migration file: " + path);
        }
        return Integer.parseInt(matcher.group(1));
    }

    private static Path locateMigrationsDirectory() throws IOException {
        Path candidate = Path.of("").toAbsolutePath();
        while (candidate != null) {
            Path migrations = candidate.resolve("database/migrations");
            if (Files.isDirectory(migrations)) {
                return migrations;
            }
            candidate = candidate.getParent();
        }
        throw new IOException("Could not locate database/migrations/");
    }
}
