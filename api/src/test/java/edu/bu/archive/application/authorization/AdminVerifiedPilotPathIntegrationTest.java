package edu.bu.archive.application.authorization;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import edu.bu.archive.adapter.out.persistence.authorization.JdbcIdentityLinkRepository;
import edu.bu.archive.adapter.out.persistence.authorization.JdbcRecordFactsRepository;
import edu.bu.archive.adapter.out.persistence.authorization.JdbcUnitHierarchy;

/**
 * Can a pilot run on ADMIN_VERIFIED links alone, with no identity import?
 *
 * <h2>The question this answers</h2>
 *
 * The DEV inventory found {@code authz.kim_principal} and
 * {@code authz.principal_crosswalk} both empty, and that was read as a blocker
 * for issuing the first grants. Tracing the code says otherwise, and this test
 * is the proof rather than the assertion.
 *
 * <p>{@link IdentityResolver} reads {@link IdentityLinkRepository} and nothing
 * else. The crosswalk tables have exactly two consumers in main - {@code
 * JdbcEnrollmentStore} and {@code IdentityEnrollmentService} - which implement
 * AUTOMATIC enrolment, the path that maps a Cognito claim to a principal
 * without an administrator. That path is not shipped: no
 * {@code CognitoProfileReader} bean exists, so enabling it fails fast at
 * startup instead of auto-linking. The only linking path that works today is
 * an administrator stating the values, and {@code
 * ArchiveAdminService.linkIdentity} takes them as parameters.
 *
 * <p>So the whole chain below runs with both crosswalk tables EMPTY, and the
 * test asserts they are still empty at the end. If a future change puts a
 * crosswalk lookup on the request path, this fails and says so.
 *
 * <h2>What it does not claim</h2>
 *
 * That the import is pointless. It makes the administrator's lookup
 * authoritative. Without it the administrator must source
 * {@code kuali_person_id} themselves, and the only place it exists is
 * {@code archive.award_person.person_id} - which is matched by name. Two
 * people sharing a name is the failure that import prevents, and the last test
 * here shows exactly what a mis-paired principal does: it grants the wrong
 * person someone else's records, silently.
 */
@Tag("database")
@Testcontainers(disabledWithoutDocker = true)
@DisplayName("A pilot on ADMIN_VERIFIED links needs no identity import")
class AdminVerifiedPilotPathIntegrationTest {

    private static final Pattern MIGRATION = Pattern.compile("^V(\\d+)__.*\\.sql$");
    private static final String ISSUER = "https://cognito-idp.us-east-1.amazonaws.com/us-east-1_SYNTHETIC";

    /** Matches archive.award_person.person_id below - the only source there is. */
    private static final String PILOT_PRINCIPAL = "P-SYN-RESEARCHER";

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg17").asCompatibleSubstituteFor("postgres"));

    private static JdbcClient jdbc;
    private static IdentityLinkRepository links;
    private static IdentityResolver resolver;
    private static RecordFactsRepository facts;
    private static UnitHierarchy units;

    @BeforeAll
    static void setUp() throws Exception {
        Path dir = Files.isDirectory(Path.of("database", "migrations"))
                ? Path.of("database", "migrations")
                : Path.of("..", "database", "migrations");
        List<Path> migrations;
        try (Stream<Path> files = Files.list(dir)) {
            migrations = files
                    .filter(p -> MIGRATION.matcher(p.getFileName().toString()).matches())
                    .sorted(Comparator.comparingInt(AdminVerifiedPilotPathIntegrationTest::version))
                    .toList();
        }

        var dataSource = new SimpleDriverDataSource(
                new org.postgresql.Driver(), postgres.getJdbcUrl(),
                postgres.getUsername(), postgres.getPassword());

        try (var connection = dataSource.getConnection(); var statement = connection.createStatement()) {
            for (Path migration : migrations) {
                statement.execute(Files.readString(migration));
            }

            statement.execute("""
                    INSERT INTO archive.unit (unit_number, unit_name, parent_unit_number) VALUES
                    ('7000', 'SCIENCES',  NULL),
                    ('7777', 'CHEMISTRY', '7000')
                    """);
            statement.execute("""
                    INSERT INTO archive.award_version (
                        award_id, award_number, sequence_number, title, status_description,
                        lead_unit_number, lead_unit_name, is_primary_current, workflow_document_number
                    ) VALUES
                    (8101, 'PILOT-00001', 1, 'Pilot award v1', 'Active', '7777', 'CHEMISTRY', FALSE, 'DOC-P-1'),
                    (8102, 'PILOT-00001', 2, 'Pilot award v2', 'Active', '7777', 'CHEMISTRY', TRUE,  'DOC-P-2'),
                    (8201, 'OTHER-00002', 1, 'Namesake award', 'Active', '7777', 'CHEMISTRY', TRUE,  'DOC-N-1')
                    """);
            /*
             * The researcher is listed as PI on version 2 only. SEC-003 says
             * that is the version they may see - being added later must not
             * hand them version 1 retrospectively.
             */
            statement.execute("""
                    INSERT INTO archive.award_person (
                        award_person_id, award_id, award_number, sequence_number,
                        full_name, contact_role_code, person_id
                    ) VALUES
                    (201, 8102, 'PILOT-00001', 2, 'SYN RESEARCHER', 'PI', 'P-SYN-RESEARCHER'),
                    -- Same display name, different principal, different Award.
                    -- This is the collision an authoritative lookup prevents.
                    (202, 8201, 'OTHER-00002', 1, 'SYN RESEARCHER', 'PI', 'P-SYN-NAMESAKE')
                    """);

            /*
             * Four pilot roles, as ADMIN_VERIFIED links. Nothing is read from
             * kim_principal or principal_crosswalk to write these - the
             * administrator supplies every value, which is the whole point.
             *
             * The no-grant control deliberately HAS a link: it must be
             * provisioned but unauthorised, so that "no records" is
             * distinguishable from "not enrolled".
             */
            statement.execute("""
                    INSERT INTO authz.identity_link (cognito_issuer, cognito_subject,
                        institutional_identifier, kuali_person_id, login_name,
                        method, status, verified_by, verified_at, revoked_by, revoked_at)
                    VALUES
                      ('%1$s', 'sub-central',  'SYN-INST-CENTRAL', NULL, 'syn-central',
                       'ADMIN_VERIFIED', 'ACTIVE', 'syn-admin', now(), NULL, NULL),
                      ('%1$s', 'sub-dept',     'SYN-INST-DEPT',    NULL, 'syn-dept',
                       'ADMIN_VERIFIED', 'ACTIVE', 'syn-admin', now(), NULL, NULL),
                      ('%1$s', 'sub-research', 'SYN-INST-RESEARCH', 'P-SYN-RESEARCHER', 'syn-research',
                       'ADMIN_VERIFIED', 'ACTIVE', 'syn-admin', now(), NULL, NULL),
                      ('%1$s', 'sub-control',  'SYN-INST-CONTROL', NULL, 'syn-control',
                       'ADMIN_VERIFIED', 'ACTIVE', 'syn-admin', now(), NULL, NULL),
                      ('%1$s', 'sub-revoked',  'SYN-INST-REVOKED', 'P-SYN-RESEARCHER', 'syn-revoked',
                       'ADMIN_VERIFIED', 'REVOKED', 'syn-admin', now(), 'syn-admin', now())
                    """.formatted(ISSUER));
        }

        jdbc = JdbcClient.create(dataSource);
        links = new JdbcIdentityLinkRepository(jdbc);
        resolver = new IdentityResolver(links);
        facts = new JdbcRecordFactsRepository(jdbc, (module, versionKey) -> Set.of());
        units = new JdbcUnitHierarchy(jdbc);
    }

    // ── the premise ──────────────────────────────────────────────────

    @Test
    @DisplayName("the identity import tables are empty for every test in this class")
    void theCrosswalkIsEmptyThroughout() {
        assertThat(count("authz.kim_principal")).isZero();
        assertThat(count("authz.principal_crosswalk")).isZero();
    }

    // ── all four pilot roles resolve ─────────────────────────────────

    @Test
    @DisplayName("each of the four pilot roles resolves to a mapped institutional identity")
    void everyPilotRoleResolves() {
        for (String subject : List.of("sub-central", "sub-dept", "sub-research", "sub-control")) {
            IdentityResolution resolution = resolver.resolve(cognito(subject));
            assertThat(resolution)
                    .as("%s must resolve without any crosswalk row", subject)
                    .isInstanceOf(IdentityResolution.Mapped.class);
        }
        assertThat(count("authz.principal_crosswalk"))
                .as("resolution must not have populated anything")
                .isZero();
    }

    @Test
    @DisplayName("the Research Staff link carries the principal that contact derivation needs")
    void theResearchStaffLinkCarriesItsPrincipal() {
        var mapped = (IdentityResolution.Mapped) resolver.resolve(cognito("sub-research"));

        assertThat(mapped.institutionalIdentifier().value()).isEqualTo("SYN-INST-RESEARCH");
        assertThat(mapped.kualiPersonId()).isPresent();
        assertThat(mapped.kualiPersonId().orElseThrow().value()).isEqualTo(PILOT_PRINCIPAL);
    }

    @Test
    @DisplayName("the other three roles carry no principal, and do not need one")
    void theOtherRolesNeedNoPrincipal() {
        /*
         * CENTRAL and UNIT are decided from the grant alone. Only contact
         * derivation reaches for a principal, so requiring one for every
         * participant would be inventing a prerequisite.
         */
        for (String subject : List.of("sub-central", "sub-dept", "sub-control")) {
            var mapped = (IdentityResolution.Mapped) resolver.resolve(cognito(subject));
            assertThat(mapped.kualiPersonId()).as("%s", subject).isEmpty();
        }
    }

    // ── the chain end to end, still with no import ───────────────────

    @Test
    @DisplayName("a UNIT grant reaches the pilot award through an admin-verified link alone")
    void aUnitGrantReachesTheAwardWithNoImport() {
        var mapped = (IdentityResolution.Mapped) resolver.resolve(cognito("sub-dept"));

        AccessScope scope = new AccessScope(
                false, Set.of(new UnitGrant("7777", false)), Optional.empty(), Set.of());

        assertThat(permits(scope, "PILOT-00001", 8102))
                .as("identity %s resolved, scope applied, record decided - no crosswalk anywhere",
                        mapped.institutionalIdentifier().value())
                .isTrue();
    }

    @Test
    @DisplayName("Research Staff reaches only the version they are listed on")
    void researchStaffReachesOnlyTheirVersion() {
        var mapped = (IdentityResolution.Mapped) resolver.resolve(cognito("sub-research"));
        AccessScope scope = new AccessScope(
                false, Set.of(), mapped.kualiPersonId(), Set.of());

        assertThat(permits(scope, "PILOT-00001", 8102))
                .as("they are PI on version 2")
                .isTrue();
        assertThat(permits(scope, "PILOT-00001", 8101))
                .as("SEC-003: being added later must not hand them the earlier version")
                .isFalse();
    }

    @Test
    @DisplayName("the no-grant control is provisioned but reaches nothing")
    void theControlIsProvisionedAndReachesNothing() {
        /*
         * The control exists to prove the difference between "you have no
         * access" and "the archive is empty". It must RESOLVE - an
         * unprovisioned caller gets a different, louder response - and then be
         * denied every record.
         */
        assertThat(resolver.resolve(cognito("sub-control")))
                .isInstanceOf(IdentityResolution.Mapped.class);

        AccessScope nothing = new AccessScope(false, Set.of(), Optional.empty(), Set.of());
        assertThat(nothing.isEmpty()).isTrue();
        assertThat(permits(nothing, "PILOT-00001", 8102)).isFalse();
        assertThat(permits(nothing, "PILOT-00001", 8101)).isFalse();
    }

    @Test
    @DisplayName("a Cognito identity with no link at all is not provisioned, which is a different answer")
    void anUnlinkedIdentityIsNotProvisioned() {
        assertThat(resolver.resolve(cognito("sub-nobody")))
                .isInstanceOf(IdentityResolution.NotProvisioned.class);
    }

    // ── identity-mapping failure modes ───────────────────────────────

    @Test
    @DisplayName("two people share a display name, and only the principal tells them apart")
    void aSharedDisplayNameIsNotAnIdentity() {
        /*
         * Both rows read 'SYN RESEARCHER'. An administrator searching the
         * archive by name finds two, on different Awards, with different
         * principals. Pairing the wrong one is undetectable afterwards -
         * which is why the verification procedure pairs against a KNOWN
         * Award rather than against a name.
         */
        List<String> principals = jdbc.sql("""
                        SELECT DISTINCT person_id FROM archive.award_person
                         WHERE full_name = 'SYN RESEARCHER' ORDER BY person_id
                        """).query(String.class).list();

        assertThat(principals)
                .as("a name resolves to more than one principal, so a name is not an identity")
                .containsExactly("P-SYN-NAMESAKE", PILOT_PRINCIPAL);

        AccessScope asNamesake = new AccessScope(
                false, Set.of(), Optional.of(new KualiPersonId("P-SYN-NAMESAKE")), Set.of());

        assertThat(permits(asNamesake, "OTHER-00002", 8201))
                .as("the namesake reaches their own Award")
                .isTrue();
        assertThat(permits(asNamesake, "PILOT-00001", 8102))
                .as("and not the pilot participant's - the two are only separable by principal")
                .isFalse();
    }

    @Test
    @DisplayName("a revoked link resolves as revoked, not as unprovisioned and not as access")
    void aRevokedLinkIsRevoked() {
        /*
         * Three outcomes have to stay distinguishable: never enrolled,
         * enrolled and withdrawn, and enrolled with no grants. Collapsing
         * revoked into not-provisioned would lose the fact that access was
         * deliberately withdrawn.
         */
        assertThat(resolverResolve("sub-revoked"))
                .isInstanceOf(IdentityResolution.Revoked.class);
    }

    @Test
    @DisplayName("a revoked link's principal does not leak access, even though the row still names it")
    void aRevokedLinkGrantsNothingDespiteItsPrincipal() {
        // The revoked row carries the SAME principal as the active research
        // link. Revocation has to stop at the link, not depend on the
        // principal being unique to it.
        IdentityResolution revoked = resolverResolve("sub-revoked");

        assertThat(revoked).isNotInstanceOf(IdentityResolution.Mapped.class);
        assertThat(revoked)
                .as("no institutional identity is handed back, so no scope can be resolved")
                .isInstanceOf(IdentityResolution.Revoked.class);
    }

    @Test
    @DisplayName("a link with no principal cannot reach anything by contact derivation")
    void aMissingPrincipalMeansNoContactAccess() {
        /*
         * The Department Administrator's link carries no kuali_person_id.
         * That is correct - they do not need one - but it means contact
         * derivation can grant them nothing, and the failure must be "no
         * access" rather than "match everything".
         */
        var mapped = (IdentityResolution.Mapped) resolverResolve("sub-dept");
        assertThat(mapped.kualiPersonId()).isEmpty();

        AccessScope contactOnly = new AccessScope(
                false, Set.of(), mapped.kualiPersonId(), Set.of());

        assertThat(contactOnly.isEmpty()).isTrue();
        assertThat(permits(contactOnly, "PILOT-00001", 8102)).isFalse();
    }

    // ── what the import would have protected ─────────────────────────

    @Test
    @DisplayName("a mis-paired principal silently grants the wrong person's records - the risk the import removes")
    void aMisPairedPrincipalGrantsTheWrongRecords() {
        /*
         * Not a defect, and nothing here can detect it: the link is
         * well-formed, the resolution succeeds and the evaluator behaves
         * correctly. The error is upstream, in the administrator pairing a
         * Cognito subject with a person_id they looked up BY NAME, because
         * archive.award_person is the only place that value exists while the
         * crosswalk is empty.
         *
         * Stated as a test so the pilot's manual verification step has a
         * reason attached rather than being a line in a runbook nobody reads.
         */
        AccessScope asTheResearcher = new AccessScope(
                false, Set.of(), Optional.of(new KualiPersonId(PILOT_PRINCIPAL)), Set.of());

        assertThat(permits(asTheResearcher, "PILOT-00001", 8102))
                .as("whoever holds this principal reaches this record, correctly and silently")
                .isTrue();

        AccessScope asSomeoneElse = new AccessScope(
                false, Set.of(), Optional.of(new KualiPersonId("P-SOMEONE-ELSE")), Set.of());

        assertThat(permits(asSomeoneElse, "PILOT-00001", 8102))
                .as("a different principal reaches nothing - so a WRONG pairing is "
                        + "indistinguishable from a right one, and only the administrator "
                        + "can tell them apart")
                .isFalse();
    }

    // ── helpers ──────────────────────────────────────────────────────

    private static IdentityResolution resolverResolve(String subject) {
        return resolver.resolve(cognito(subject));
    }

    private static boolean permits(AccessScope scope, String awardNumber, long awardId) {
        List<RecordFacts> family = facts.awardFamily(awardNumber);
        RecordFacts version = family.stream()
                .filter(record -> record.versionKey().equals(Long.toString(awardId)))
                .findFirst()
                .orElseThrow();
        return new RecordAccessEvaluator(policy(), units)
                .decide(new AccessOutcome.Scoped(scope), version, family)
                .allowed();
    }

    private static AuthorizationPolicy policy() {
        return new AuthorizationPolicy(
                AuthorizationPolicy.VersionScope.FAMILY_WIDE,
                AuthorizationPolicy.DepartmentMatch.EXACT_LEAD_UNIT,
                Set.of("PI"));
    }

    private static ValidatedCognitoIdentity cognito(String subject) {
        return new ValidatedCognitoIdentity(ISSUER, subject, subject, Instant.now(), Set.of());
    }

    private static long count(String table) {
        return jdbc.sql("SELECT COUNT(*) FROM " + table).query(Long.class).single();
    }

    private static int version(Path path) {
        Matcher matcher = MIGRATION.matcher(path.getFileName().toString());
        return matcher.matches() ? Integer.parseInt(matcher.group(1)) : Integer.MAX_VALUE;
    }
}
