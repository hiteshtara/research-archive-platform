package edu.bu.archive.application.authorization;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import edu.bu.archive.adapter.out.persistence.authorization.JdbcRecordFactsRepository;
import edu.bu.archive.adapter.out.persistence.authorization.JdbcUnitHierarchy;

/**
 * SEC-003 holds under BOTH version scopes, in SQL as well as in Java.
 *
 * <h2>The risk</h2>
 *
 * {@code FAMILY_WIDE} exists to widen a department or IO grant from one
 * matching version to the whole family. Contact-derived access must NOT be
 * widened the same way: being listed on version 2 must never reach version 1,
 * because someone added to an Award partway through did not have it before.
 *
 * <p>Both halves already implement that, in different ways and in different
 * files. {@code RecordAccessEvaluator} evaluates {@code contactMatches}
 * against {@code record} rather than the family it considered for the other
 * grants. {@code RecordScopeSqlBuilder} ORs the contact clause OUTSIDE
 * {@code familyWrap}. Two separate mechanisms that have to agree, where the
 * failure is silent and the blast radius is every Award with history.
 *
 * <p>So this runs both of them against real rows, under each version scope,
 * and compares the sets. The existing tests pin one half or the other, or pin
 * both under {@code FAMILY_WIDE} only - {@code RecordScopeSqlBuilderTest}
 * checks rendered SQL strings without a database, and the Award suite's
 * policy is hard-coded to {@code FAMILY_WIDE}.
 *
 * <h2>Why a department grant appears in a SEC-003 test</h2>
 *
 * As a positive control. If the fixture were arranged so that nothing widens
 * under {@code FAMILY_WIDE}, every assertion here would pass against a build
 * where {@code familyWrap} did nothing at all, and the test would prove only
 * that it had not been run.
 */
@Tag("database")
@Testcontainers(disabledWithoutDocker = true)
@DisplayName("SEC-003: contact access stays version-specific under every version scope")
class Sec003VersionScopeAgreementIntegrationTest {

    private static final Pattern MIGRATION = Pattern.compile("^V(\\d+)__.*\\.sql$");

    /** Listed as PI on version 2 of FAM-00001, and nowhere else. */
    private static final KualiPersonId STAFF = new KualiPersonId("P-SEC003-STAFF");

    /** Only version 2 carries this unit; versions 1 and 3 carry UNIT_OTHER. */
    private static final String UNIT_V2 = "7777";
    private static final String UNIT_OTHER = "9999";

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg17").asCompatibleSubstituteFor("postgres"));

    private static JdbcClient jdbc;
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
                    .sorted(Comparator.comparingInt(Sec003VersionScopeAgreementIntegrationTest::version))
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
                    ('7777', 'CHEMISTRY', NULL),
                    ('9999', 'PHYSICS',   NULL),
                    ('8888', 'BIOLOGY',   NULL)
                    """);
            /*
             * One family, three versions. Only version 2 is in UNIT_V2, and
             * only version 2 lists the researcher. That separation is what
             * makes the two rules distinguishable: a department grant on
             * UNIT_V2 must widen to all three under FAMILY_WIDE, while the
             * contact listing on the same version must not widen at all.
             */
            statement.execute("""
                    INSERT INTO archive.award_version (
                        award_id, award_number, sequence_number, title, status_description,
                        lead_unit_number, lead_unit_name, is_primary_current, workflow_document_number
                    ) VALUES
                    (7001, 'FAM-00001', 1, 'Family v1', 'Active', '9999', 'PHYSICS',   FALSE, 'DOC-F-1'),
                    (7002, 'FAM-00001', 2, 'Family v2', 'Active', '7777', 'CHEMISTRY', FALSE, 'DOC-F-2'),
                    (7003, 'FAM-00001', 3, 'Family v3', 'Active', '9999', 'PHYSICS',   TRUE,  'DOC-F-3'),
                    (7101, 'OTHER-0001', 1, 'Other',    'Active', '8888', 'BIOLOGY',   TRUE,  'DOC-O-1')
                    """);
            statement.execute("""
                    INSERT INTO archive.award_person (
                        award_person_id, award_id, award_number, sequence_number,
                        full_name, contact_role_code, person_id
                    ) VALUES
                    (301, 7002, 'FAM-00001', 2, 'SEC003 STAFF', 'PI', 'P-SEC003-STAFF')
                    """);
        }

        jdbc = JdbcClient.create(dataSource);
        facts = new JdbcRecordFactsRepository(jdbc, (module, versionKey) -> Set.of());
        units = new JdbcUnitHierarchy(jdbc);
    }

    // ── the rule itself, under each scope ────────────────────────────

    @ParameterizedTest(name = "{0}")
    @EnumSource(AuthorizationPolicy.VersionScope.class)
    @DisplayName("a contact listed on one version reaches that version and no other")
    void contactAccessIsVersionSpecificUnderEveryScope(AuthorizationPolicy.VersionScope scope) {
        List<Long> selected = selectedBySql(contactScope(), scope);

        assertThat(selected)
                .as("FAMILY_WIDE must not widen a contact listing; PER_VERSION never would")
                .containsExactly(7002L);

        assertThat(allowedByEvaluator(contactScope(), scope))
                .as("and the single-record path must say the same thing")
                .containsExactly(7002L);
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(AuthorizationPolicy.VersionScope.class)
    @DisplayName("the SQL predicate and the evaluator agree on every row, under every scope")
    void theTwoHalvesAgreeUnderEveryScope(AuthorizationPolicy.VersionScope scope) {
        for (AccessScope access : List.of(contactScope(), departmentScope(UNIT_V2), combinedScope())) {
            assertThat(selectedBySql(access, scope))
                    .as("scope=%s access=%s", scope, describe(access))
                    .containsExactlyInAnyOrderElementsOf(allowedByEvaluator(access, scope));
        }
    }

    // ── the positive control ─────────────────────────────────────────

    @Test
    @DisplayName("FAMILY_WIDE really does widen a department grant - so the test above is not vacuous")
    void familyWideWidensADepartmentGrant() {
        /*
         * Only version 2 is in UNIT_V2. Under PER_VERSION a grant on that unit
         * reaches version 2 alone; under FAMILY_WIDE it reaches all three.
         * If this ever returned the same set for both, familyWrap would be
         * doing nothing and every SEC-003 assertion in this class would be
         * passing for the wrong reason.
         */
        assertThat(selectedBySql(departmentScope(UNIT_V2), AuthorizationPolicy.VersionScope.PER_VERSION))
                .containsExactly(7002L);

        assertThat(selectedBySql(departmentScope(UNIT_V2), AuthorizationPolicy.VersionScope.FAMILY_WIDE))
                .containsExactly(7001L, 7002L, 7003L);
    }

    @Test
    @DisplayName("a department grant widening the family does not drag in another family")
    void wideningStopsAtTheFamilyBoundary() {
        /*
         * OTHER-0001 sits in its own unit, so it is out of scope on its own
         * merits. What is being checked is that FAMILY_WIDE's widening is
         * bounded by familyKey - it reaches the other versions of FAM-00001
         * and stops, rather than reaching every version in the table.
         */
        assertThat(selectedBySql(departmentScope(UNIT_V2), AuthorizationPolicy.VersionScope.FAMILY_WIDE))
                .containsExactly(7001L, 7002L, 7003L)
                .doesNotContain(7101L);
    }

    // ── the interaction that matters: an independent grant ───────────

    @Test
    @DisplayName("an independently authorized grant may reach versions the contact listing does not")
    void anIndependentGrantStillReachesWhatItIsEntitledTo() {
        /*
         * SEC-003 restricts what CONTACT DERIVATION reaches. It is not a
         * ceiling on the person: someone who is both a contact on version 2
         * and a department administrator for UNIT_OTHER legitimately sees
         * versions 1 and 3 through the second grant. Reading SEC-003 as "a
         * contact may only ever see their own version" would silently narrow
         * a department grant the business did approve.
         */
        AccessScope both = new AccessScope(
                false, Set.of(new UnitGrant(UNIT_OTHER, false)), Optional.of(STAFF), Set.of());

        assertThat(selectedBySql(both, AuthorizationPolicy.VersionScope.PER_VERSION))
                .as("versions 1 and 3 by department, version 2 by contact listing")
                .containsExactly(7001L, 7002L, 7003L);

        assertThat(allowedByEvaluator(both, AuthorizationPolicy.VersionScope.PER_VERSION))
                .containsExactly(7001L, 7002L, 7003L);
    }

    @Test
    @DisplayName("a contact on no version of a family reaches none of it, under either scope")
    void aStrangerReachesNothing() {
        AccessScope stranger = new AccessScope(
                false, Set.of(), Optional.of(new KualiPersonId("P-NOBODY")), Set.of());

        for (AuthorizationPolicy.VersionScope scope : AuthorizationPolicy.VersionScope.values()) {
            assertThat(selectedBySql(stranger, scope)).as("%s", scope).isEmpty();
            assertThat(allowedByEvaluator(stranger, scope)).as("%s", scope).isEmpty();
        }
    }

    @Test
    @DisplayName("an unapproved contact role grants nothing, however the scope is set")
    void anUnapprovedRoleGrantsNothing() {
        // The policy below approves PI only. The listing is a PI listing, so
        // narrowing the approved set must take it away.
        AuthorizationPolicy coiOnly = new AuthorizationPolicy(
                AuthorizationPolicy.VersionScope.FAMILY_WIDE,
                AuthorizationPolicy.DepartmentMatch.EXACT_LEAD_UNIT,
                Set.of("COI"));

        SqlFragment fragment = new RecordScopeSqlBuilder(coiOnly, IoSqlStrategy.NONE)
                .build(new AccessOutcome.Scoped(contactScope()), RecordModule.AWARD, "av", Set.of());

        assertThat(run(fragment)).isEmpty();
    }

    // ── helpers ──────────────────────────────────────────────────────

    private static List<Long> selectedBySql(AccessScope access, AuthorizationPolicy.VersionScope scope) {
        SqlFragment fragment = new RecordScopeSqlBuilder(policy(scope), IoSqlStrategy.NONE)
                .build(new AccessOutcome.Scoped(access), RecordModule.AWARD, "av",
                        access.units().stream().map(UnitGrant::unitNumber).collect(java.util.stream.Collectors.toSet()));
        return run(fragment);
    }

    private static List<Long> run(SqlFragment fragment) {
        var statement = jdbc.sql("SELECT av.award_id FROM archive.award_version av WHERE TRUE "
                + fragment.sql() + " ORDER BY av.award_id");
        for (var parameter : fragment.params().entrySet()) {
            statement = statement.param(parameter.getKey(), parameter.getValue());
        }
        return statement.query(Long.class).list();
    }

    private static List<Long> allowedByEvaluator(AccessScope access, AuthorizationPolicy.VersionScope scope) {
        var evaluator = new RecordAccessEvaluator(policy(scope), units);
        List<RecordFacts> family = facts.awardFamily("FAM-00001");
        List<RecordFacts> other = facts.awardFamily("OTHER-0001");

        return Stream.concat(
                        family.stream().map(version -> decide(evaluator, access, version, family)),
                        other.stream().map(version -> decide(evaluator, access, version, other)))
                .filter(java.util.Objects::nonNull)
                .sorted()
                .toList();
    }

    private static Long decide(RecordAccessEvaluator evaluator, AccessScope access,
                               RecordFacts version, List<RecordFacts> family) {
        return evaluator.decide(new AccessOutcome.Scoped(access), version, family).allowed()
                ? Long.valueOf(version.versionKey())
                : null;
    }

    private static AccessScope contactScope() {
        return new AccessScope(false, Set.of(), Optional.of(STAFF), Set.of());
    }

    private static AccessScope departmentScope(String unit) {
        return new AccessScope(false, Set.of(new UnitGrant(unit, false)), Optional.empty(), Set.of());
    }

    private static AccessScope combinedScope() {
        return new AccessScope(false, Set.of(new UnitGrant(UNIT_V2, false)), Optional.of(STAFF), Set.of());
    }

    private static String describe(AccessScope scope) {
        return "units=" + scope.units().size() + " contact=" + scope.contactPersonId().isPresent();
    }

    private static AuthorizationPolicy policy(AuthorizationPolicy.VersionScope scope) {
        return new AuthorizationPolicy(
                scope, AuthorizationPolicy.DepartmentMatch.EXACT_LEAD_UNIT, Set.of("PI"));
    }

    private static int version(Path path) {
        Matcher matcher = MIGRATION.matcher(path.getFileName().toString());
        return matcher.matches() ? Integer.parseInt(matcher.group(1)) : Integer.MAX_VALUE;
    }
}
