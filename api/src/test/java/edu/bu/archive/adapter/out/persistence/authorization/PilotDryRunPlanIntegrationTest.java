package edu.bu.archive.adapter.out.persistence.authorization;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
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
import edu.bu.archive.application.authorization.admin.provisioning.IdentityLinkCommand;
import edu.bu.archive.application.authorization.admin.provisioning.IdentityLinkOutcome;
import edu.bu.archive.application.authorization.admin.provisioning.IdentityLinkRequest;
import edu.bu.archive.application.authorization.admin.provisioning.RuntimeIdentity;

/**
 * The eight pilot roles, run as DRY RUNS, so the plan is output and not prose.
 *
 * <h2>Why this is a test and not a document</h2>
 *
 * <p>A proposed provisioning plan written by hand is a guess about what the
 * utilities will say. These are the utilities actually saying it: every line
 * in {@code docs/runbooks/DEV_PILOT_DRY_RUNS.md} is copied from this test's
 * output, against a real schema with every committed migration applied.
 *
 * <p>Nothing is written anywhere. Every request below omits {@code --confirm},
 * which is the utilities' default, and the test asserts that the database is
 * untouched afterwards - so this file cannot become a way to provision
 * something by accident.
 *
 * <p>The identifiers are placeholders by design. Real pilot identities come
 * from BU IAM and Cognito and must be handed over out of band, never
 * committed - see PHASE2_PILOT_PLAN section 4b. What this proves is the SHAPE
 * of each run: which flags, which refusals, which notes.
 */
@Testcontainers(disabledWithoutDocker = true)
@Tag("database")
@DisplayName("The eight pilot roles, as dry runs")
class PilotDryRunPlanIntegrationTest {

    private static final Pattern MIGRATION_VERSION = Pattern.compile("^V(\\d+)__.*\\.sql$");
    private static final String ACCOUNT = "770203350335";
    private static final String ENVIRONMENT = "dev";
    private static final String POOL = "us-east-1_VJ4ekQ27c";
    private static final String REGION = "us-east-1";
    private static final String ISSUER = "https://cognito-idp.us-east-1.amazonaws.com/" + POOL;
    private static final String ADMIN = "ARP-ADMN0001-00000001";

    /** Real DEV scope values, read from the live API on 2026-11-10. */
    private static final String PARENT_UNIT = "1250000000";
    private static final String IO_ACCOUNT = "9500234095";
    private static final String PARENT_AWARD = "100380-00001";
    private static final String PI_PERSON_ID = "1000570";

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg17")
                    .asCompatibleSubstituteFor("postgres"));

    private static JdbcClient jdbc;
    private static PlatformTransactionManager txManager;
    private static ArchiveAdminService admin;
    private static AdminRoleRepository adminRoles;
    private static AdminIdentityRepository identities;
    private static AccessGrantRepository grants;

    /** One pilot role: who they are, what they get, and what it proves. */
    private record Role(
            int number,
            String name,
            String identifier,
            String cognitoSubject,
            boolean archiveAdmin,
            String grantType,
            String unit,
            boolean descendants,
            String io,
            String awardNumber,
            String kualiPersonId,
            String proves) {
    }

    private static final List<Role> ROLES = List.of(
            new Role(1, "Central User", "ARP-PKT00001-00000001",
                    "11111111-1111-1111-1111-111111111111", false,
                    "CENTRAL", null, false, null, null, null,
                    "SEC-002: the widest scope works at all; Dashboard totals unchanged"),
            new Role(2, "Department User", "ARP-PKT00002-00000002",
                    "22222222-2222-2222-2222-222222222222", false,
                    "UNIT", PARENT_UNIT, false, null, null, null,
                    "SEC-002: an Award at the unit is visible, one below it is not"),
            new Role(3, "Department User with descendants", "ARP-PKT00003-00000003",
                    "33333333-3333-3333-3333-333333333333", false,
                    "UNIT", PARENT_UNIT, true, null, null, null,
                    "SEC-002: expansion is transitive, not one level"),
            new Role(4, "Research Staff", "ARP-PKT00004-00000004",
                    "44444444-4444-4444-4444-444444444444", false,
                    "CONTACT_DERIVATION", null, false, null, null, PI_PERSON_ID,
                    "SEC-003: contact-derived access, version-specific"),
            new Role(5, "SAP IO User", "ARP-PKT00005-00000005",
                    "55555555-5555-5555-5555-555555555555", false,
                    "IO", null, false, IO_ACCOUNT, null, null,
                    "SEC-004: IO scope reaches the Award whose account number matches, "
                            + "and no Proposal, Subaward or Negotiation"),
            new Role(6, "Parent Award User", "ARP-PKT00006-00000006",
                    "66666666-6666-6666-6666-666666666666", false,
                    "PARENT_AWARD", null, false, null, PARENT_AWARD, null,
                    "SEC-005: the granted Award plus its children at the configured "
                            + "depth, and nothing from another Award family"),
            new Role(7, "Grant Administrator", "ARP-PKT00007-00000007",
                    "77777777-7777-7777-7777-777777777777", true,
                    null, null, false, null, null, null,
                    "administering access is not having it - no grant, sees nothing"),
            new Role(8, "No-Grant Control", "ARP-PKT00008-00000008",
                    "88888888-8888-8888-8888-888888888888", false,
                    null, null, false, null, null, null,
                    "an authenticated user with no grant sees nothing, and is told why"));

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
                    .sorted(Comparator.comparingInt(PilotDryRunPlanIntegrationTest::version))
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
        identities = proxy(new JdbcAdminIdentityRepository(jdbc), AdminIdentityRepository.class);
        admin = proxyClass(new ArchiveAdminService(
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
        admin.bootstrapFirstAdministratorWithIdentity(
                new InstitutionalIdentifier(ADMIN), "operator@bu.edu", "pilot dry run",
                ISSUER, "00000000-0000-0000-0000-000000000001", null, "admin@bu.edu");
        jdbc.sql("TRUNCATE authz.access_audit").update();
    }

    @Test
    void everyRolesDryRunPassesAndWritesNothing() {
        List<String> report = new ArrayList<>();
        report.add("Generated by PilotDryRunPlanIntegrationTest against a throwaway");
        report.add("PostgreSQL with every committed migration applied. No DEV contact.");
        report.add("");

        for (Role role : ROLES) {
            report.add("=".repeat(72));
            report.add(String.format("ROLE %d - %s", role.number(), role.name()));
            report.add("Proves: " + role.proves());
            report.add("=".repeat(72));

            IdentityLinkOutcome link = linkCommand().run(linkRequest(role));
            report.add("");
            report.add("STEP 4  link-identity  (dry run)");
            report.add("  result: " + link.result());
            link.checks().forEach(line -> report.add("  " + line));
            link.problems().forEach(line -> report.add("  FAIL   " + line));
            report.add("  " + link.message());

            assertThat(link.result())
                    .as("role %d's link dry run must pass", role.number())
                    .isEqualTo(IdentityLinkOutcome.Result.DRY_RUN_OK);

            report.add("");
            if (role.grantType() == null) {
                report.add("STEP 5  grant-admin    NOT RUN - this role receives no grant.");
                report.add("  Running one would spend the pilot's control. Roles 7 and 8");
                report.add("  exist to show that an authenticated user with no grant sees");
                report.add("  nothing, and that administering access is not having it.");
            } else {
                GrantOutcome grant = grantCommand().run(grantRequest(role));
                report.add("STEP 5  grant-admin    (dry run)");
                report.add("  result: " + grant.result());
                grant.checks().forEach(line -> report.add("  " + line));
                grant.problems().forEach(line -> report.add("  FAIL   " + line));
                report.add("  " + grant.message());

                assertThat(grant.result())
                        .as("role %d's grant dry run must pass", role.number())
                        .isEqualTo(GrantOutcome.Result.DRY_RUN_OK);
            }
            report.add("");
        }

        // The whole point of a dry run.
        assertThat(activeLinks()).as("no link was written").isZero();
        assertThat(activeGrants()).as("no grant was written").isZero();
        assertThat(auditRows()).as("no audit row was written").isZero();

        System.out.println(String.join("\n", report));
    }

    @Test
    void theAdministratorAndTheControlAreNotGivenGrants() {
        /*
         * The pilot's two controls. If either ever receives a grant the pilot
         * stops proving the thing it exists to prove, so it is asserted here
         * rather than left to a reviewer noticing.
         */
        for (Role role : ROLES) {
            if (role.number() == 7 || role.number() == 8) {
                assertThat(role.grantType())
                        .as("role %d is a control and must receive no grant", role.number())
                        .isNull();
            } else {
                assertThat(role.grantType())
                        .as("role %d must receive a grant or it tests nothing", role.number())
                        .isNotNull();
            }
        }
    }

    @Test
    void onlyTheResearchStaffRoleCarriesAKualiPersonId() {
        /*
         * A PERSON_ID on a link that does not need one is not harmless: it
         * enables contact-derived access that nobody granted. And its absence
         * on role 4 would make that role's grant inert - the trap the
         * provisioning utilities now refuse.
         */
        for (Role role : ROLES) {
            if ("CONTACT_DERIVATION".equals(role.grantType())) {
                assertThat(role.kualiPersonId()).isNotNull();
            } else {
                assertThat(role.kualiPersonId())
                        .as("role %d does not need a PERSON_ID", role.number())
                        .isNull();
            }
        }
    }

    @Test
    void everyRoleCoversADistinctRequirement() {
        assertThat(ROLES).hasSize(8);
        assertThat(ROLES.stream().map(Role::identifier).distinct().count()).isEqualTo(8);
        assertThat(ROLES.stream().map(Role::cognitoSubject).distinct().count()).isEqualTo(8);

        List<String> types = ROLES.stream().map(Role::grantType).filter(t -> t != null).toList();
        assertThat(types)
                .as("IO and PARENT_AWARD were added so SEC-004 and SEC-005 are "
                        + "observed on DEV and not only in tests")
                .contains("CENTRAL", "UNIT", "CONTACT_DERIVATION", "IO", "PARENT_AWARD");
    }

    // ---------------- helpers ----------------

    private static IdentityLinkRequest linkRequest(Role role) {
        return new IdentityLinkRequest(
                ACCOUNT, ENVIRONMENT, POOL, REGION, ISSUER,
                role.cognitoSubject(), role.identifier(), ADMIN,
                role.kualiPersonId(), "pilot" + role.number() + "@bu.edu",
                "operator@bu.edu",
                "eight-role authorization pilot, role " + role.number() + ": " + role.name(),
                "APPROVAL-PENDING",
                false, false, null);
    }

    private static GrantRequest grantRequest(Role role) {
        return new GrantRequest(
                ACCOUNT, ENVIRONMENT, role.identifier(), ADMIN, role.grantType(),
                role.unit(), role.descendants(), role.io(), role.awardNumber(),
                "operator@bu.edu",
                "eight-role authorization pilot, role " + role.number() + ": " + role.name(),
                "APPROVAL-PENDING",
                false, false,
                // D-ADM-3: no expiry by default. The pilot's grants are
                // proposed WITH one, so an unreviewed pilot expires instead of
                // becoming permanent by inattention.
                "2027-06-30T00:00:00Z",
                null);
    }

    private static IdentityLinkCommand linkCommand() {
        return new IdentityLinkCommand(ENVIRONMENT, admin, adminRoles, identities, runtime());
    }

    private static GrantProvisioningCommand grantCommand() {
        return new GrantProvisioningCommand(
                ACCOUNT, ENVIRONMENT, admin, adminRoles, grants, identities, runtime());
    }

    private static RuntimeIdentity runtime() {
        return new RuntimeIdentity() {
            @Override
            public Optional<String> awsAccountId() {
                return Optional.of(ACCOUNT);
            }

            @Override
            public Optional<String> clusterName() {
                return Optional.of("research-archive-platform-dev-api");
            }
        };
    }

    private static int activeLinks() {
        return jdbc.sql("SELECT count(*) FROM authz.identity_link WHERE status = 'ACTIVE'"
                + " AND institutional_identifier <> :admin")
                .param("admin", ADMIN).query(Integer.class).single();
    }

    private static int activeGrants() {
        return jdbc.sql("SELECT count(*) FROM authz.access_grant WHERE revoked_at IS NULL")
                .query(Integer.class).single();
    }

    private static int auditRows() {
        return jdbc.sql("SELECT count(*) FROM authz.access_audit").query(Integer.class).single();
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
