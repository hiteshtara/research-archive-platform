package edu.bu.archive.adapter.out.persistence.authorization;

import edu.bu.archive.adapter.in.web.dto.me.CapabilitiesResponse;
import edu.bu.archive.application.authorization.AuthorizationProperties;
import edu.bu.archive.application.authorization.CurrentIdentityProvider;
import edu.bu.archive.application.authorization.IdentityResolution;
import edu.bu.archive.application.authorization.IdentityResolver;
import edu.bu.archive.application.authorization.InstitutionalIdentifier;
import edu.bu.archive.application.authorization.ValidatedCognitoIdentity;
import edu.bu.archive.application.authorization.admin.AdminRoleRepository;
import edu.bu.archive.application.me.CapabilitiesService;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Statement;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/*
 * The capability that cannot be proven with mocks: that administrator
 * status is readable from the REAL authz.admin_role_member table with
 * enforcement OFF.
 *
 * That is the whole reason GET /api/v1/me/capabilities exists. The
 * administrator API tree is gated on enforcement and returns 404 when it
 * is off, so a mocked repository could happily "work" while the real read
 * depended on something only present when enforcement is on. This runs
 * every committed migration against a real PostgreSQL, inserts a real
 * membership row, and asserts the service reads it - with
 * app.authorization.enforcement-enabled left FALSE throughout.
 *
 * Only the identity chain is mocked, because a Cognito JWT cannot be
 * manufactured here. Everything from the InstitutionalIdentifier down is
 * real.
 *
 * Skipped automatically when no Docker daemon is available. Run it with:
 *
 *   mvn test -Dtest=CapabilitiesMembershipIntegrationTest
 *
 * Fixtures are synthetic.
 */
@Tag("database")
@Testcontainers(disabledWithoutDocker = true)
class CapabilitiesMembershipIntegrationTest {

    private static final Pattern MIGRATION_VERSION = Pattern.compile("^V(\\d+)__.*\\.sql$");

    private static final InstitutionalIdentifier ADMIN =
            new InstitutionalIdentifier("capadmin@bu.edu");
    private static final InstitutionalIdentifier NOT_ADMIN =
            new InstitutionalIdentifier("ordinary@bu.edu");
    private static final InstitutionalIdentifier REVOKED_ADMIN =
            new InstitutionalIdentifier("formeradmin@bu.edu");

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg17").asCompatibleSubstituteFor("postgres"));

    private static AdminRoleRepository adminRoles;

    @BeforeAll
    static void applyMigrationsAndSeed() throws Exception {
        List<Path> migrations;
        try (Stream<Path> files = Files.list(locateMigrationsDirectory())) {
            migrations = files
                    .filter(p -> MIGRATION_VERSION.matcher(p.getFileName().toString()).matches())
                    .sorted(Comparator.comparingInt(
                            CapabilitiesMembershipIntegrationTest::migrationVersion))
                    .toList();
        }

        SimpleDriverDataSource dataSource = new SimpleDriverDataSource();
        dataSource.setDriverClass(org.postgresql.Driver.class);
        dataSource.setUrl(postgres.getJdbcUrl());
        dataSource.setUsername(postgres.getUsername());
        dataSource.setPassword(postgres.getPassword());

        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement()) {
            for (Path migration : migrations) {
                statement.execute(Files.readString(migration));
            }

            // One ACTIVE membership and one REVOKED one, inserted directly
            // so the READ is what is under test, not the write path.
            // V084's ck_admin_role_member_revoked / _revoked_by require a
            // REVOKED row to carry both revoked_at and revoked_by, so the
            // revoked fixture supplies them rather than working around the
            // constraint.
            statement.execute("""
                    INSERT INTO authz.admin_role_member (
                        institutional_identifier, status, granted_by, granted_at, reason
                    ) VALUES
                    ('capadmin@bu.edu', 'ACTIVE', 'test-setup', NOW(), 'integration fixture')
                    """);
            statement.execute("""
                    INSERT INTO authz.admin_role_member (
                        institutional_identifier, status, granted_by, granted_at,
                        revoked_by, revoked_at, reason
                    ) VALUES
                    ('formeradmin@bu.edu', 'REVOKED', 'test-setup', NOW(),
                     'test-setup', NOW(), 'integration fixture')
                    """);
        }

        adminRoles = new JdbcAdminRoleRepository(JdbcClient.create(dataSource));
    }

    private CapabilitiesService serviceFor(IdentityResolution resolution, boolean enforcement) {
        CurrentIdentityProvider identities = mock(CurrentIdentityProvider.class);
        when(identities.current()).thenReturn(Optional.of(mock(ValidatedCognitoIdentity.class)));

        IdentityResolver resolver = mock(IdentityResolver.class);
        when(resolver.resolve(any())).thenReturn(resolution);

        AuthorizationProperties properties = new AuthorizationProperties();
        properties.setEnforcementEnabled(enforcement);

        return new CapabilitiesService(identities, resolver, adminRoles, properties);
    }

    private IdentityResolution mapped(InstitutionalIdentifier who) {
        return new IdentityResolution.Mapped(
                mock(ValidatedCognitoIdentity.class), who, Optional.empty());
    }

    @Test
    void theRealMembershipTableIsReadableWithEnforcementOff() {
        CapabilitiesResponse actual = serviceFor(mapped(ADMIN), false).current();

        assertThat(actual.administrator())
                .as("an ACTIVE row in authz.admin_role_member must be visible with "
                        + "enforcement OFF - the property the admin API cannot offer")
                .isTrue();
        assertThat(actual.enforcementEnabled()).isFalse();
        assertThat(actual.administrationAvailable()).isFalse();
    }

    @Test
    void theSameRealMembershipWithEnforcementOnIsFullyAvailable() {
        CapabilitiesResponse actual = serviceFor(mapped(ADMIN), true).current();

        assertThat(actual.administrator()).isTrue();
        assertThat(actual.enforcementEnabled()).isTrue();
        assertThat(actual.administrationAvailable()).isTrue();
    }

    @Test
    void anIdentityWithNoRowAtAllIsNotAnAdministrator() {
        CapabilitiesResponse actual = serviceFor(mapped(NOT_ADMIN), true).current();

        assertThat(actual.administrator()).isFalse();
        assertThat(actual.enforcementEnabled()).isFalse();
        assertThat(actual.administrationAvailable()).isFalse();
    }

    @Test
    void aRevokedMembershipRowIsNotAnAdministrator() {
        // findActive filters on status = 'ACTIVE' in SQL. Proven against the
        // real table rather than against a stubbed Optional.
        assertThat(serviceFor(mapped(REVOKED_ADMIN), true).current().administrator()).isFalse();
    }

    @Test
    void aSuspendedIdentityHoldingTheRealActiveRowIsStillNotAnAdministrator() {
        // The ACTIVE row for capadmin@bu.edu genuinely exists in the table.
        // Suspension must override it, and does so structurally: a Suspended
        // resolution carries no InstitutionalIdentifier to look up.
        CapabilitiesResponse actual = serviceFor(
                new IdentityResolution.Suspended(mock(ValidatedCognitoIdentity.class)), true)
                .current();

        assertThat(actual.administrator()).isFalse();
        assertThat(actual.enforcementEnabled()).isFalse();
        assertThat(actual.administrationAvailable()).isFalse();
    }

    @Test
    void theFixtureIsRealSoTheseAssertionsAreNotVacuous() {
        // Guard: had the INSERT silently not happened, every assertion above
        // would pass for the wrong reason.
        assertThat(adminRoles.findActive(ADMIN)).isPresent();
        assertThat(adminRoles.findActive(REVOKED_ADMIN)).isEmpty();
        assertThat(adminRoles.findActive(NOT_ADMIN)).isEmpty();
        assertThat(adminRoles.countActive()).isGreaterThanOrEqualTo(1);
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
