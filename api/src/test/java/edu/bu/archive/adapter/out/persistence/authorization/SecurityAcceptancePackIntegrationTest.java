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
import org.junit.jupiter.api.Nested;
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

import edu.bu.archive.application.authorization.AccessDecision;
import edu.bu.archive.application.authorization.AccessGrantRepository;
import edu.bu.archive.application.authorization.AccessOutcome;
import edu.bu.archive.application.authorization.AccessScopeResolver;
import edu.bu.archive.application.authorization.AuthorizationPolicy;
import edu.bu.archive.application.authorization.IdentityResolver;
import edu.bu.archive.application.authorization.InstitutionalIdentifier;
import edu.bu.archive.application.authorization.KualiPersonId;
import edu.bu.archive.application.authorization.RecordAccessEvaluator;
import edu.bu.archive.application.authorization.RecordFacts;
import edu.bu.archive.application.authorization.ValidatedCognitoIdentity;
import edu.bu.archive.application.authorization.admin.AdminIdentityRepository;
import edu.bu.archive.application.authorization.admin.AdminRoleRepository;
import edu.bu.archive.application.authorization.admin.ArchiveAdminService;
import edu.bu.archive.application.authorization.admin.GrantSpecification;
import edu.bu.archive.application.authorization.GrantType;

/**
 * The executable security acceptance pack.
 *
 * <h2>What this is for</h2>
 *
 * <p>{@code docs/security/AUTHORIZATION_ACCEPTANCE_MATRIX.md} numbers twenty
 * acceptance cases and states, in its own words, that because no grants exist
 * and enforcement is off, "every 'expected' below is a prediction to be
 * verified, not an observation". This file is where the predictions become
 * observations. Each test is named for its matrix case so the two can be read
 * against each other, and a case that changes in one has to change in both.
 *
 * <h2>Why it is not covered by the per-module tests</h2>
 *
 * <p>Every module has an authorization integration test, and they are
 * thorough, but they all construct an {@link
 * edu.bu.archive.application.authorization.AccessScope} directly. That tests
 * the half of the chain from a scope to a decision. The acceptance matrix is
 * written in terms of GRANTS, and the half from a grant row to a scope - the
 * half an operator actually performs - was exercised nowhere end to end.
 *
 * <p>So this pack starts where a real request starts, with a Cognito issuer
 * and subject, and runs the whole chain:
 *
 * <pre>
 *   ValidatedCognitoIdentity
 *     -> IdentityResolver        (authz.identity_link)
 *     -> AccessScopeResolver     (authz.access_grant, hierarchy, contacts)
 *     -> RecordAccessEvaluator   (the policy)
 *     -> AccessDecision
 * </pre>
 *
 * <p>The grants themselves are written through {@link ArchiveAdminService},
 * the same method the administrative API and both operator utilities call, so
 * no fixture can hold a grant shape the real path would have refused.
 *
 * <h2>Enforcement</h2>
 *
 * <p>These tests run with a policy present, which is what "enforcement on"
 * means to the engine. They are safe because the database is a throwaway
 * container: nothing here enables enforcement on a deployed service, and no
 * DEV grant, link or setting is touched.
 */
@Testcontainers(disabledWithoutDocker = true)
@Tag("database")
@DisplayName("Security acceptance pack (AUTHORIZATION_ACCEPTANCE_MATRIX)")
class SecurityAcceptancePackIntegrationTest {

    private static final Pattern MIGRATION_VERSION = Pattern.compile("^V(\\d+)__.*\\.sql$");

    private static final String ISSUER = "https://cognito-idp.us-east-1.amazonaws.com/us-east-1_TEST";
    private static final String ADMIN = "ARP-ADMN0001-00000001";

    /** Mirrors the matrix's real DEV units: parent, child, grandchild. */
    private static final String PARENT_UNIT = "1250000000";
    private static final String CHILD_UNIT = "1250400000";
    private static final String GRANDCHILD_UNIT = "1252060000";

    /** One Award per level, so "unit only" and "unit + descendants" separate. */
    private static final String AWARD_AT_PARENT = "212137-00001";
    private static final String AWARD_AT_CHILD = "114471-00001";
    private static final String AWARD_AT_GRANDCHILD = "200613-00001";
    private static final String AWARD_WITH_ACCOUNT = "100004-00002";
    private static final String ACCOUNT_NUMBER = "9500234095";
    private static final String PI_PERSON_ID = "1000570";

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg17")
                    .asCompatibleSubstituteFor("postgres"));

    private static JdbcClient jdbc;
    private static PlatformTransactionManager txManager;
    private static ArchiveAdminService admin;
    private static AdminRoleRepository adminRoles;
    private static AdminIdentityRepository adminIdentities;
    private static AccessGrantRepository grants;
    private static JdbcRecordFactsRepository facts;
    private static IdentityResolver identityResolver;
    /**
     * The real clock, deliberately, where the rest of this suite uses a fixed
     * one.
     *
     * <p>A grant's {@code valid_from} is set by the database to the moment it
     * is written, and {@code AccessGrant.isActiveAt} treats a grant as not yet
     * active while {@code now} is before it. A fixed clock is therefore always
     * in the past by the time the grant exists, and every allow case here
     * failed with NO_ACTIVE_GRANTS - a grant that had just been issued
     * successfully reading as inactive.
     *
     * <p>Nothing here asserts on an expiry boundary, so there is no
     * determinism to lose; the cases that do judge expiry live in the grant
     * provisioning suite, which injects its own fixed clock for that purpose.
     */
    private static final Clock CLOCK = Clock.systemUTC();

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
                    .sorted(Comparator.comparingInt(
                            SecurityAcceptancePackIntegrationTest::version))
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
        adminIdentities = proxy(new JdbcAdminIdentityRepository(jdbc), AdminIdentityRepository.class);
        admin = proxyClass(new ArchiveAdminService(
                adminRoles,
                proxy(new JdbcAdminAuditSink(jdbc),
                        edu.bu.archive.application.authorization.admin.AdminAuditSink.class),
                grants, adminIdentities, new JdbcAdminAuditRepository(jdbc)));

        facts = new JdbcRecordFactsRepository(jdbc, new AwardAccountNumberIoResolver(jdbc));
        identityResolver = new IdentityResolver(new JdbcIdentityLinkRepository(jdbc));

        seedArchive();
    }

    @BeforeEach
    void resetAuthorization() {
        jdbc.sql("DELETE FROM authz.access_grant").update();
        jdbc.sql("DELETE FROM authz.identity_link").update();
        jdbc.sql("DELETE FROM authz.admin_role_member").update();
        jdbc.sql("DELETE FROM authz.person_status").update();
        jdbc.sql("TRUNCATE authz.access_audit").update();

        admin.bootstrapFirstAdministratorWithIdentity(
                new InstitutionalIdentifier(ADMIN), "operator@bu.edu", "acceptance pack",
                ISSUER, subjectFor(ADMIN), null, "admin@bu.edu");
    }

    // ── cases 1 to 5: CENTRAL and the unit hierarchy ─────────────────

    @Nested
    @DisplayName("Cases 1-5: CENTRAL and unit scope")
    class UnitScope {

        @Test
        @DisplayName("1: CENTRAL sees any Award")
        void case1() {
            String user = link("ARP-CENTRAL1-00000001");
            grant(user, GrantType.CENTRAL, null, false, null, null);

            assertThat(canSee(user, AWARD_AT_PARENT)).isTrue();
            assertThat(canSee(user, AWARD_AT_CHILD)).isTrue();
            assertThat(canSee(user, AWARD_AT_GRANDCHILD)).isTrue();
        }

        @Test
        @DisplayName("2: UNIT without descendants sees the Award at that unit")
        void case2() {
            String user = link("ARP-UNIT0001-00000001");
            grant(user, GrantType.UNIT, PARENT_UNIT, false, null, null);

            assertThat(canSee(user, AWARD_AT_PARENT)).isTrue();
        }

        @Test
        @DisplayName("3: UNIT without descendants is denied the child unit's Award")
        void case3() {
            String user = link("ARP-UNIT0001-00000001");
            grant(user, GrantType.UNIT, PARENT_UNIT, false, null, null);

            assertThat(canSee(user, AWARD_AT_CHILD))
                    .as("a unit grant must not leak downward unless descendants were asked for")
                    .isFalse();
        }

        @Test
        @DisplayName("4: UNIT with descendants sees the child unit's Award")
        void case4() {
            String user = link("ARP-DESC0001-00000001");
            grant(user, GrantType.UNIT, PARENT_UNIT, true, null, null);

            assertThat(canSee(user, AWARD_AT_CHILD)).isTrue();
        }

        @Test
        @DisplayName("5: descendant expansion is transitive, not one level")
        void case5() {
            String user = link("ARP-DESC0001-00000001");
            grant(user, GrantType.UNIT, PARENT_UNIT, true, null, null);

            assertThat(canSee(user, AWARD_AT_GRANDCHILD))
                    .as("1252060000 is a child of 1250400000, which is a child of the granted unit")
                    .isTrue();
        }
    }

    // ── cases 12 to 14: contact derivation and IO ────────────────────

    @Nested
    @DisplayName("Cases 12-14: contact derivation and IO")
    class DerivedScope {

        @Test
        @DisplayName("12: CONTACT_DERIVATION with a PERSON_ID holding a PI row sees that Award")
        void case12() {
            String user = link("ARP-STAFF001-00000001", new KualiPersonId(PI_PERSON_ID));
            grant(user, GrantType.CONTACT_DERIVATION, null, false, null, null);

            assertThat(canSee(user, AWARD_AT_PARENT))
                    .as("award_person carries person_id %s with role PI on this Award", PI_PERSON_ID)
                    .isTrue();
        }

        @Test
        @DisplayName("13: CONTACT_DERIVATION with no PERSON_ID is denied everything")
        void case13() {
            /*
             * The grant exists and is active. Access is still nil, because the
             * contact path is only added when the mapping carries a PERSON_ID.
             * This is the case the provisioning utilities now refuse to create
             * in the first place - here it is proved at the decision end.
             */
            String user = link("ARP-STAFF002-00000002");
            grant(user, GrantType.CONTACT_DERIVATION, null, false, null, null);

            assertThat(canSee(user, AWARD_AT_PARENT)).isFalse();
            assertThat(canSee(user, AWARD_AT_CHILD)).isFalse();

            /*
             * And the sharper point: the resolver collapses an empty scope to
             * NO_ACTIVE_GRANTS, so this identity is indistinguishable from one
             * holding no grant at all - while authz.access_grant holds a live
             * row and the audit trail records that access was granted.
             *
             * That is the whole reason the provisioning utilities refuse this
             * combination at the point of issue. Nothing downstream can tell
             * an administrator that the grant they are looking at does
             * nothing.
             */
            assertThat(jdbc.sql("""
                            SELECT count(*) FROM authz.access_grant
                            WHERE institutional_identifier = :g AND revoked_at IS NULL
                            """).param("g", user).query(Integer.class).single())
                    .as("the grant really is there and live")
                    .isEqualTo(1);
            assertThat(scopeOf(user))
                    .isInstanceOf(AccessOutcome.NotProvisioned.class);
            assertThat(((AccessOutcome.NotProvisioned) scopeOf(user)).reason())
                    .as("a live grant reported as if no grant existed")
                    .isEqualTo(AccessOutcome.NotProvisionedReason.NO_ACTIVE_GRANTS);
        }

        @Test
        @DisplayName("14: IO sees the Award whose account number matches")
        void case14() {
            String user = link("ARP-IOGRANT-00000001");
            grant(user, GrantType.IO, null, false, ACCOUNT_NUMBER, null);

            assertThat(canSee(user, AWARD_WITH_ACCOUNT)).isTrue();
            assertThat(canSee(user, AWARD_AT_PARENT))
                    .as("a different Award with no matching account number")
                    .isFalse();
        }
    }

    // ── cases 16 to 20: the negative controls ────────────────────────

    @Nested
    @DisplayName("Cases 16-20: the negative controls")
    class NegativeControls {

        @Test
        @DisplayName("16: a linked identity with no grant sees nothing (the No-Grant Control)")
        void case16() {
            String user = link("ARP-NOGRANT1-00000001");

            assertThat(scopeOf(user))
                    .isInstanceOf(AccessOutcome.NotProvisioned.class);
            assertThat(((AccessOutcome.NotProvisioned) scopeOf(user)).reason())
                    .isEqualTo(AccessOutcome.NotProvisionedReason.NO_ACTIVE_GRANTS);
            assertThat(canSee(user, AWARD_AT_PARENT)).isFalse();
        }

        @Test
        @DisplayName("17: a revoked grant denies what it used to permit")
        void case17() {
            String user = link("ARP-REVOKED1-00000001");
            long grantId = grant(user, GrantType.UNIT, PARENT_UNIT, false, null, null);

            assertThat(canSee(user, AWARD_AT_PARENT))
                    .as("permitted while the grant is live")
                    .isTrue();

            admin.revokeAccess(new InstitutionalIdentifier(ADMIN), grantId, "acceptance pack");

            assertThat(canSee(user, AWARD_AT_PARENT))
                    .as("revocation is a status change and must take effect immediately")
                    .isFalse();
        }

        @Test
        @DisplayName("18: a suspended identity is denied despite a live grant")
        void case18() {
            String user = link("ARP-SUSPEND1-00000001");
            grant(user, GrantType.CENTRAL, null, false, null, null);
            assertThat(canSee(user, AWARD_AT_PARENT)).isTrue();

            admin.setSuspended(new InstitutionalIdentifier(ADMIN),
                    new InstitutionalIdentifier(user), true, "acceptance pack");

            assertThat(scopeOf(user)).isInstanceOf(AccessOutcome.Denied.class);
            assertThat(((AccessOutcome.Denied) scopeOf(user)).reason())
                    .isEqualTo(AccessOutcome.DenialReason.SUSPENDED_IDENTITY);
            assertThat(canSee(user, AWARD_AT_PARENT))
                    .as("suspension outranks a CENTRAL grant")
                    .isFalse();
        }

        @Test
        @DisplayName("19: an unlinked Cognito identity is denied, and is not merely ungranted")
        void case19() {
            AccessOutcome outcome = resolveScope("99999999-0000-0000-0000-999999999999");

            assertThat(outcome).isInstanceOf(AccessOutcome.NotProvisioned.class);
            assertThat(((AccessOutcome.NotProvisioned) outcome).reason())
                    .as("the two unprovisioned reasons must stay distinguishable to an administrator")
                    .isEqualTo(AccessOutcome.NotProvisionedReason.NO_IDENTITY_LINK);
        }

        @Test
        @DisplayName("20: ARCHIVE_ADMIN alone confers no access to any record")
        void case20() {
            /*
             * The property the pilot's Grant Administrator depends on. The
             * administrator is linked and holds an ACTIVE membership; they
             * hold no access_grant row, and must therefore see nothing.
             */
            assertThat(adminRoles.findActive(new InstitutionalIdentifier(ADMIN)))
                    .as("the administrator really is one")
                    .isNotEmpty();
            assertThat(jdbc.sql("SELECT count(*) FROM authz.access_grant")
                    .query(Integer.class).single())
                    .isZero();

            assertThat(canSee(ADMIN, AWARD_AT_PARENT)).isFalse();
            assertThat(canSee(ADMIN, AWARD_AT_CHILD)).isFalse();
            assertThat(canSee(ADMIN, AWARD_WITH_ACCOUNT)).isFalse();
        }

        @Test
        @DisplayName("20b: administering access does not grant it, even over the records administered")
        void case20b() {
            /*
             * A sharper form of the same rule. The administrator issues a
             * CENTRAL grant to somebody else - the widest grant there is - and
             * still sees nothing themselves.
             */
            String user = link("ARP-CENTRAL1-00000001");
            grant(user, GrantType.CENTRAL, null, false, null, null);

            assertThat(canSee(user, AWARD_AT_PARENT)).isTrue();
            assertThat(canSee(ADMIN, AWARD_AT_PARENT))
                    .as("issuing a grant must not reflect any access back onto the issuer")
                    .isFalse();
        }
    }

    // ── denial is indistinguishable from absence ─────────────────────

    @Nested
    @DisplayName("Denied versus missing")
    class DeniedVersusMissing {

        @Test
        @DisplayName("an out-of-scope Award and a non-existent one answer identically")
        void outOfScopeAndAbsentAreTheSame() {
            /*
             * Tested as a PAIR, as the matrix requires. Asserting each
             * separately would pass while the two answers differed, and the
             * difference is the disclosure.
             */
            String user = link("ARP-UNIT0001-00000001");
            grant(user, GrantType.UNIT, PARENT_UNIT, false, null, null);

            AccessDecision outOfScope = decide(user, AWARD_AT_CHILD);
            Optional<String> absent = facts.currentAwardVersionKey("NO-SUCH-AWARD");

            assertThat(absent)
                    .as("the control record must genuinely not exist")
                    .isEmpty();
            assertThat(outOfScope.allowed()).isFalse();
            assertThat(outOfScope.reasons())
                    .as("a denial carries no reason that describes the record")
                    .isEmpty();
        }

        @Test
        @DisplayName("a denial carries no record data at all")
        void aDenialCarriesNoRecordData() {
            String user = link("ARP-UNIT0001-00000001");
            grant(user, GrantType.UNIT, PARENT_UNIT, false, null, null);

            AccessDecision decision = decide(user, AWARD_AT_CHILD);

            assertThat(decision.allowed()).isFalse();
            assertThat(String.valueOf(decision))
                    .as("neither the unit nor the award number may appear in a denial")
                    .doesNotContain(CHILD_UNIT)
                    .doesNotContain(AWARD_AT_CHILD);
        }
    }

    // ── the matrix and this file must not drift ──────────────────────

    @Test
    @DisplayName("every numbered matrix case is either executed here or listed as out of scope")
    void theMatrixAndThePackAgree() throws Exception {
        /*
         * The matrix is the document a reviewer reads; this file is the proof.
         * If a case is added there and not here, the matrix quietly goes back
         * to being a prediction, which is the state this pack exists to end.
         *
         * Cases 6-11 and 15 are Proposal, Subaward and Negotiation cases,
         * covered by those modules' own authorization integration tests. They
         * are named here so that "not in this file" is a recorded decision
         * rather than an omission.
         */
        Set<Integer> coveredElsewhere = Set.of(6, 7, 8, 9, 10, 11, 15);
        Set<Integer> coveredHere = Set.of(1, 2, 3, 4, 5, 12, 13, 14, 16, 17, 18, 19, 20);

        String matrix = Files.readString(locate("docs/security/AUTHORIZATION_ACCEPTANCE_MATRIX.md"));
        Matcher rows = Pattern.compile("^\\| (\\d+) \\|", Pattern.MULTILINE).matcher(matrix);

        List<Integer> numbered = new java.util.ArrayList<>();
        while (rows.find()) {
            numbered.add(Integer.valueOf(rows.group(1)));
        }

        assertThat(numbered)
                .as("the matrix must still be a numbered table this test can read")
                .isNotEmpty();
        for (Integer caseNumber : numbered) {
            assertThat(coveredHere.contains(caseNumber) || coveredElsewhere.contains(caseNumber))
                    .as("matrix case %d is neither executed here nor recorded as covered elsewhere",
                            caseNumber)
                    .isTrue();
        }
    }

    // ---------------- the chain under test ----------------

    /**
     * The whole decision, from a Cognito identity to an allow or a deny.
     *
     * <p>Nothing is short-circuited: the link is read from
     * {@code authz.identity_link}, the grants from {@code authz.access_grant},
     * the hierarchy and contacts from the archive tables, and the policy is
     * the one a configured environment would carry.
     */
    private static AccessDecision decide(String identifier, String awardNumber) {
        AccessOutcome outcome = scopeOf(identifier);
        List<RecordFacts> family = facts.awardFamily(awardNumber);
        Optional<String> current = facts.currentAwardVersionKey(awardNumber);

        if (family.isEmpty() || current.isEmpty()) {
            throw new IllegalStateException("fixture missing award " + awardNumber);
        }
        RecordFacts record = family.stream()
                .filter(f -> f.versionKey().equals(current.get()))
                .findFirst()
                .orElseThrow();

        return evaluator().decide(outcome, record, family);
    }

    private static boolean canSee(String identifier, String awardNumber) {
        return decide(identifier, awardNumber).allowed();
    }

    private static AccessOutcome scopeOf(String identifier) {
        return resolveScope(subjectFor(identifier));
    }

    private static AccessOutcome resolveScope(String cognitoSubject) {
        return new AccessScopeResolver(
                grants, CLOCK, SecurityAcceptancePackIntegrationTest::policy,
                new JdbcContactRelationships(jdbc), new JdbcAwardHierarchy(jdbc))
                .resolve(identityResolver.resolve(new ValidatedCognitoIdentity(
                        ISSUER, cognitoSubject, "user", CLOCK.instant())));
    }

    private static RecordAccessEvaluator evaluator() {
        return new RecordAccessEvaluator(policy().orElseThrow(), new JdbcUnitHierarchy(jdbc));
    }

    /**
     * The policy these cases are judged under.
     *
     * <p>Chosen to match the matrix: family-wide version scope,
     * explicit-grant contact derivation, and the research staff roles that
     * actually exist in DEV.
     *
     * <p>DepartmentMatch is LEAD_UNIT_WITH_DESCENDANTS because cases 2-5
     * distinguish a unit grant WITH descendants from one without, and
     * EXACT_LEAD_UNIT ignores that flag entirely - under it, cases 4 and 5
     * would fail for the right reason and case 3 would pass for the wrong
     * one.
     */
    private static Optional<AuthorizationPolicy> policy() {
        return Optional.of(new AuthorizationPolicy(
                AuthorizationPolicy.VersionScope.FAMILY_WIDE,
                AuthorizationPolicy.DepartmentMatch.LEAD_UNIT_WITH_DESCENDANTS,
                Set.of("PI", "COI", "KP", "MPI"),
                1,
                AuthorizationPolicy.ContactDerivation.EXPLICIT_GRANT));
    }

    // ---------------- fixtures ----------------

    private static void seedArchive() {
        jdbc.sql("""
                INSERT INTO archive.unit (unit_number, unit_name, parent_unit_number) VALUES
                (:parent, 'PARENT UNIT', NULL),
                (:child, 'CHILD UNIT', :parent),
                (:grandchild, 'GRANDCHILD UNIT', :child)
                """)
                .param("parent", PARENT_UNIT)
                .param("child", CHILD_UNIT)
                .param("grandchild", GRANDCHILD_UNIT)
                .update();

        jdbc.sql("""
                INSERT INTO archive.award_version (
                    award_id, award_number, sequence_number, title,
                    status_description, sponsor_code, sponsor_name,
                    lead_unit_number, lead_unit_name, award_effective_date,
                    is_primary_current, workflow_document_number, account_number
                ) VALUES
                (9001, :atParent, 1, 'At the parent unit', 'Active',
                 '0200', 'NIH', :parent, 'PARENT UNIT', DATE '2020-01-01', TRUE, 'DOC-1', NULL),
                (9002, :atChild, 1, 'At the child unit', 'Active',
                 '0200', 'NIH', :child, 'CHILD UNIT', DATE '2020-01-01', TRUE, 'DOC-2', NULL),
                (9003, :atGrandchild, 1, 'At the grandchild unit', 'Active',
                 '0200', 'NIH', :grandchild, 'GRANDCHILD UNIT', DATE '2020-01-01', TRUE, 'DOC-3', NULL),
                (9004, :withAccount, 1, 'Carries an account number', 'Active',
                 '0100', 'NSF', :parent, 'PARENT UNIT', DATE '2020-01-01', TRUE, 'DOC-4', :account)
                """)
                .param("atParent", AWARD_AT_PARENT)
                .param("atChild", AWARD_AT_CHILD)
                .param("atGrandchild", AWARD_AT_GRANDCHILD)
                .param("withAccount", AWARD_WITH_ACCOUNT)
                .param("parent", PARENT_UNIT)
                .param("child", CHILD_UNIT)
                .param("grandchild", GRANDCHILD_UNIT)
                .param("account", ACCOUNT_NUMBER)
                .update();

        // The PI contact row that makes case 12 resolvable.
        jdbc.sql("""
                INSERT INTO archive.award_person (
                    award_person_id, award_id, award_number, sequence_number,
                    person_id, full_name, contact_role_code
                ) VALUES
                (1, 9001, :atParent, 1, :person, 'A Principal Investigator', 'PI')
                """)
                .param("atParent", AWARD_AT_PARENT)
                .param("person", PI_PERSON_ID)
                .update();
    }

    /**
     * A deterministic Cognito subject per archive identity.
     *
     * <p>Derived rather than listed so that a new identity in a test cannot
     * accidentally reuse another's subject - which the database would refuse
     * as a duplicate link, failing for a reason unrelated to the case.
     */
    private static String subjectFor(String identifier) {
        String hex = String.format("%08x", Math.abs(identifier.hashCode()));
        return hex + "-0000-0000-0000-" + String.format("%012d", Math.abs(identifier.hashCode() % 1000000));
    }

    /** Link an identity with no Kuali principal. */
    private static String link(String identifier) {
        return link(identifier, null);
    }

    private static String link(String identifier, KualiPersonId principal) {
        admin.linkIdentity(new InstitutionalIdentifier(ADMIN), ISSUER, subjectFor(identifier),
                new InstitutionalIdentifier(identifier), principal, identifier + "@bu.edu",
                "acceptance pack");
        return identifier;
    }

    /** Issue a grant through the real administrative path. */
    private static long grant(String grantee, GrantType type, String unit,
            boolean descendants, String io, String awardNumber) {

        return admin.grantAccess(
                new InstitutionalIdentifier(ADMIN),
                new InstitutionalIdentifier(grantee),
                new GrantSpecification(type, unit, descendants, io, awardNumber, null,
                        "acceptance pack"))
                .id();
    }

    // ---------------- plumbing ----------------

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

    private static Path locate(String relative) {
        Path candidate = Path.of(relative);
        return Files.exists(candidate) ? candidate : Path.of("..", relative);
    }
}
