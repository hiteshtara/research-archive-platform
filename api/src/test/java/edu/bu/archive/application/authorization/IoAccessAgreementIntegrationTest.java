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
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import edu.bu.archive.adapter.out.persistence.authorization.AwardAccountNumberIoResolver;
import edu.bu.archive.adapter.out.persistence.authorization.AwardAccountNumberIoSql;
import edu.bu.archive.adapter.out.persistence.authorization.JdbcRecordFactsRepository;

/**
 * SEC-004: do the two halves of the IO rule actually agree?
 *
 * <h2>Why this exists</h2>
 *
 * IO access is decided twice, by two different pieces of code that never meet:
 *
 * <ul>
 *   <li>{@link AwardAccountNumberIoSql} renders a predicate that filters LIST
 *       queries - this is what a search or a dashboard count runs;
 *   <li>{@link AwardAccountNumberIoResolver} reads one record's account
 *       numbers so {@link RecordAccessEvaluator} can decide a SINGLE record -
 *       this is what opening a record by URL runs.
 * </ul>
 *
 * Every other scope has an equivalence test pinning those two halves together.
 * IO did not. {@code AwardRecordAuthorizationIntegrationTest} says so in a
 * comment at its fixture: it wires {@link IoSqlStrategy#NONE}, so "the Java
 * side must resolve no IO values either, or the two halves of the equivalence
 * test would be comparing different rules". Production wires neither of those -
 * {@code AuthorizationConfiguration} supplies the real resolver and the real
 * SQL strategy - so the configuration that actually ships was the one
 * configuration nothing compared.
 *
 * <p>A disagreement here is not cosmetic. If SQL is wider than the evaluator, a
 * search lists rows that refuse to open. If the evaluator is wider, a record
 * opens that search will not show - and a count that disagrees with its own
 * page is how the existence of an invisible record leaks.
 *
 * <h2>What it does NOT do</h2>
 *
 * Enforcement stays OFF in DEV. This builds a throwaway PostgreSQL, applies the
 * committed migrations to it, and exercises the real classes against rows
 * shaped like real Award rows. It touches no shared database and issues no
 * grant.
 */
@Tag("database")
@Testcontainers(disabledWithoutDocker = true)
@DisplayName("SEC-004 IO access: the SQL predicate and the evaluator agree")
class IoAccessAgreementIntegrationTest {

    private static final Pattern MIGRATION = Pattern.compile("^V(\\d+)__.*\\.sql$");

    private static final String GRANTED_IO = "IO-55500";

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg17").asCompatibleSubstituteFor("postgres"));

    private static JdbcClient jdbc;
    private static RecordFactsRepository facts;
    private static UnitHierarchy unitHierarchy;

    @BeforeAll
    static void setUp() throws Exception {
        Path dir = Files.isDirectory(Path.of("database", "migrations"))
                ? Path.of("database", "migrations")
                : Path.of("..", "database", "migrations");
        List<Path> migrations;
        try (Stream<Path> files = Files.list(dir)) {
            migrations = files
                    .filter(p -> MIGRATION.matcher(p.getFileName().toString()).matches())
                    .sorted(Comparator.comparingInt(IoAccessAgreementIntegrationTest::version))
                    .toList();
        }

        var dataSource = new SimpleDriverDataSource(
                new org.postgresql.Driver(), postgres.getJdbcUrl(),
                postgres.getUsername(), postgres.getPassword());

        try (var connection = dataSource.getConnection(); var statement = connection.createStatement()) {
            for (Path migration : migrations) {
                statement.execute(Files.readString(migration));
            }
            /*
             * Account numbers are deliberately awkward, because the rule trims
             * and the storage does not:
             *
             *   IO-A1  account number exactly as granted
             *   IO-A2  same number, padded - TRIM must still match it
             *   IO-B1  a different account number
             *   IO-C1  empty-string account number
             *   IO-D1  NULL account number
             *
             * Only IO-A1 and IO-A2 may ever be visible to a grant for
             * IO-55500. The last three are the ones a sloppy predicate leaks:
             * an unguarded IN () over NULL, or an empty string matching an
             * empty grant value.
             */
            statement.execute("""
                    INSERT INTO archive.award_version (
                        award_id, award_number, sequence_number, title,
                        status_description, lead_unit_number, lead_unit_name,
                        account_number, is_primary_current, workflow_document_number
                    ) VALUES
                    (9101, 'IO-A-00001', 1, 'Exact account',   'Active', '7777', 'CHEMISTRY', 'IO-55500',   TRUE, 'DOC-IO-A1'),
                    (9102, 'IO-A-00002', 1, 'Padded account',  'Active', '7777', 'CHEMISTRY', '  IO-55500 ', TRUE, 'DOC-IO-A2'),
                    (9201, 'IO-B-00001', 1, 'Other account',   'Active', '7777', 'CHEMISTRY', 'IO-99999',   TRUE, 'DOC-IO-B1'),
                    (9301, 'IO-C-00001', 1, 'Empty account',   'Active', '7777', 'CHEMISTRY', '',           TRUE, 'DOC-IO-C1'),
                    (9401, 'IO-D-00001', 1, 'No account',      'Active', '7777', 'CHEMISTRY', NULL,         TRUE, 'DOC-IO-D1')
                    """);
        }

        jdbc = JdbcClient.create(dataSource);
        // The REAL resolver, not a stub that returns nothing.
        facts = new JdbcRecordFactsRepository(jdbc, new AwardAccountNumberIoResolver(jdbc));
        unitHierarchy = new edu.bu.archive.adapter.out.persistence.authorization.JdbcUnitHierarchy(jdbc);
    }

    // ── the equivalence that was missing ─────────────────────────────

    @Test
    @DisplayName("every Award row the IO predicate selects is one the evaluator also permits, and vice versa")
    void theIoPredicateAndTheEvaluatorAgreeOnEveryAwardRow() {
        List<String> selectedBySql = awardNumbersMatching(ioScope(GRANTED_IO));

        List<String> permittedByEvaluator = allAwardNumbers().stream()
                .filter(awardNumber -> evaluatorPermits(awardNumber, GRANTED_IO))
                .toList();

        assertThat(selectedBySql)
                .as("the list predicate and the single-record evaluator are separate code paths; "
                        + "a row in one and not the other is either a leak or a record that "
                        + "lists but refuses to open")
                .containsExactlyInAnyOrderElementsOf(permittedByEvaluator);

        assertThat(selectedBySql)
                .as("TRIM means the padded account number matches; nothing else may")
                .containsExactlyInAnyOrder("IO-A-00001", "IO-A-00002");
    }

    @Test
    @DisplayName("an empty or NULL account number is never reachable by an IO grant")
    void blankAccountNumbersAreNotReachable() {
        /*
         * The two rows most likely to leak. An IN () over a NULL column yields
         * NULL - correctly excluded - but a predicate written with COALESCE or
         * a grant carrying an empty value would sweep them both in, which is
         * every Award that was never given an account number.
         */
        assertThat(awardNumbersMatching(ioScope(GRANTED_IO)))
                .doesNotContain("IO-C-00001", "IO-D-00001");

        assertThat(evaluatorPermits("IO-C-00001", GRANTED_IO)).isFalse();
        assertThat(evaluatorPermits("IO-D-00001", GRANTED_IO)).isFalse();
    }

    @Test
    @DisplayName("a grant for an account number nobody holds reaches nothing, rather than everything")
    void anUnmatchedIoValueReachesNothing() {
        assertThat(awardNumbersMatching(ioScope("IO-00000-NOBODY"))).isEmpty();
    }

    // ── the approved boundary: Awards only (decision D-A) ────────────

    @Test
    @DisplayName("IO reaches Awards only - Proposal, Subaward and Negotiation get no IO predicate")
    void ioDoesNotTravelToTheOtherModules() {
        AwardAccountNumberIoSql strategy = new AwardAccountNumberIoSql();

        assertThat(strategy.ioPredicate(RecordModule.AWARD, "av"))
                .as("Awards carry the account number, so this is the one module IO applies to")
                .isPresent();

        for (RecordModule module : List.of(
                RecordModule.PROPOSAL, RecordModule.SUBAWARD, RecordModule.NEGOTIATION)) {
            assertThat(strategy.ioPredicate(module, "x"))
                    .as("%s must get no IO predicate - an absent predicate denies, "
                            + "whereas a present-but-empty one would match every row", module)
                    .isEmpty();
        }
    }

    // ── what an expired or revoked grant leaves behind ───────────────

    @Test
    @DisplayName("an IO scope emptied by expiry or revocation denies every row, rather than matching all of them")
    void anEmptiedIoScopeDeniesEverything() {
        /*
         * AccessScopeResolver already drops expired, revoked and not-yet-valid
         * grants - AccessScopeResolverTest pins that. What it leaves behind is
         * a scope with NO io values, and that is the dangerous shape: an
         * unguarded "account_number IN ()" is a syntax error, and a predicate
         * built from an empty set with the guard written the other way round
         * matches every row. So the failure mode is not "the expired grant
         * still works" - it is "the expired grant now sees the whole archive".
         */
        AccessScope emptied = new AccessScope(false, Set.of(), Optional.empty(), Set.of());

        assertThat(emptied.isEmpty()).isTrue();
        assertThat(awardNumbersMatching(emptied))
                .as("an empty scope must select nothing; selecting everything is the "
                        + "catastrophic reading of the same data")
                .isEmpty();

        for (String awardNumber : allAwardNumbers()) {
            assertThat(evaluatorPermitsScope(awardNumber, emptied))
                    .as("%s must also be denied by the single-record path", awardNumber)
                    .isFalse();
        }
    }

    @Test
    @DisplayName("the live grant really does reach rows - the empty-scope test is not passing vacuously")
    void aLiveIoScopeStillReaches() {
        assertThat(awardNumbersMatching(ioScope(GRANTED_IO))).isNotEmpty();
    }

    // ── helpers ──────────────────────────────────────────────────────

    /** The SQL half: render the real predicate and run it. */
    private static List<String> awardNumbersMatching(AccessScope scope) {
        SqlFragment fragment = new RecordScopeSqlBuilder(policy(), new AwardAccountNumberIoSql())
                .build(new AccessOutcome.Scoped(scope), RecordModule.AWARD, "av", Set.of());

        // The fragment is a conjunct - it begins " AND ..." - so it needs a
        // standing WHERE TRUE to attach to. An empty fragment therefore selects
        // EVERY row, which is precisely why the empty-scope case is tested.
        var query = jdbc.sql("SELECT av.award_number FROM archive.award_version av WHERE TRUE "
                + fragment.sql());
        for (var parameter : fragment.params().entrySet()) {
            query = query.param(parameter.getKey(), parameter.getValue());
        }
        return query.query(String.class).list().stream().sorted().toList();
    }

    /** The evaluator half: ask about one record, through the real IO resolver. */
    private static boolean evaluatorPermits(String awardNumber, String grantedIo) {
        return evaluatorPermitsScope(awardNumber, ioScope(grantedIo));
    }

    private static boolean evaluatorPermitsScope(String awardNumber, AccessScope scope) {
        List<RecordFacts> family = facts.awardFamily(awardNumber);
        RecordFacts record = family.get(0);
        AccessDecision decision = new RecordAccessEvaluator(policy(), unitHierarchy)
                .decide(new AccessOutcome.Scoped(scope), record, family);
        return decision.allowed();
    }

    private static AccessScope ioScope(String ioValue) {
        return new AccessScope(false, Set.of(), Optional.empty(), Set.of(ioValue));
    }

    private static AuthorizationPolicy policy() {
        return new AuthorizationPolicy(
                AuthorizationPolicy.VersionScope.FAMILY_WIDE,
                AuthorizationPolicy.DepartmentMatch.EXACT_LEAD_UNIT,
                Set.of("PI"));
    }

    private static List<String> allAwardNumbers() {
        return jdbc.sql("SELECT award_number FROM archive.award_version ORDER BY award_number")
                .query(String.class).list();
    }

    private static int version(Path path) {
        Matcher matcher = MIGRATION.matcher(path.getFileName().toString());
        return matcher.matches() ? Integer.parseInt(matcher.group(1)) : Integer.MAX_VALUE;
    }
}
