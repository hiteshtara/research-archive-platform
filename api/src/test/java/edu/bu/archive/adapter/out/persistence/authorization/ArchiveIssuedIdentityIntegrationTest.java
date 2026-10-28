package edu.bu.archive.adapter.out.persistence.authorization;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Statement;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import edu.bu.archive.application.authorization.ArchivePrincipalId;
import edu.bu.archive.application.authorization.IdentityResolution;
import edu.bu.archive.application.authorization.IdentityResolver;
import edu.bu.archive.application.authorization.InstitutionalIdentifier;
import edu.bu.archive.application.authorization.ValidatedCognitoIdentity;
import edu.bu.archive.application.authorization.admin.AdminIdentityRepository;

/**
 * The archive-issued identity model: grants keyed on an identifier the archive
 * mints, rather than on a BU attribute nobody has confirmed yet.
 *
 * <p>These tests exist to prove the three things that must never happen:
 * two identities colliding, an identity being reassigned, and one person
 * inheriting another's grants.
 */
@Testcontainers
class ArchiveIssuedIdentityIntegrationTest {

    private static final Pattern MIGRATION_VERSION = Pattern.compile("^V(\\d+)__.*\\.sql$");
    private static final String ISSUER = "https://cognito-idp.us-east-1.amazonaws.com/pool";

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg17")
                    .asCompatibleSubstituteFor("postgres"));

    private static JdbcClient jdbc;
    private static PlatformTransactionManager txManager;
    private static AdminIdentityRepository identities;
    private static IdentityResolver resolver;
    private static TransactionTemplate inTransaction;

    @BeforeAll
    static void applyMigrations() throws Exception {
        List<Path> migrations;
        try (Stream<Path> files = Files.list(locateMigrationsDirectory())) {
            migrations = files
                    .filter(p -> MIGRATION_VERSION.matcher(p.getFileName().toString()).matches())
                    .sorted(Comparator.comparingInt(
                            ArchiveIssuedIdentityIntegrationTest::migrationVersion))
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
        txManager = new DataSourceTransactionManager(dataSource);
        inTransaction = new TransactionTemplate(txManager);

        var repository = new JdbcAdminIdentityRepository(jdbc);
        var factory = new ProxyFactory(repository);
        factory.setInterfaces(AdminIdentityRepository.class);
        factory.addAdvice(new TransactionInterceptor(
                txManager, new AnnotationTransactionAttributeSource()));
        identities = (AdminIdentityRepository) factory.getProxy();

        resolver = new IdentityResolver(new JdbcIdentityLinkRepository(jdbc));
    }

    @BeforeEach
    void reset() {
        jdbc.sql("DELETE FROM authz.access_grant").update();
        jdbc.sql("DELETE FROM authz.identity_link").update();
        jdbc.sql("DELETE FROM authz.person_status").update();
    }

    @Nested
    @DisplayName("Identities cannot collide")
    class CannotCollide {

        @Test
        void mintedIdentifiersAreUniqueAcrossManyDraws() {
            Set<String> seen = new HashSet<>();
            for (int i = 0; i < 20_000; i++) {
                assertThat(seen.add(ArchivePrincipalId.mint().value()))
                        .as("a repeat inside 20,000 draws would mean far too little entropy")
                        .isTrue();
            }
        }

        @Test
        void aMintedIdentifierIsNeverDerivedFromThePerson() {
            /*
             * Two people with everything in common must still get unrelated
             * identifiers. mint() takes no argument at all, which is the
             * structural guarantee; this asserts the consequence.
             */
            assertThat(ArchivePrincipalId.mint().value())
                    .isNotEqualTo(ArchivePrincipalId.mint().value());
        }

        @Test
        void theFormatRejectsTranscriptionSlipsRatherThanResolvingThem() {
            // Crockford base32 omits I, L, O and U precisely so that these are
            // rejected values rather than a different person's identity.
            for (String slip : List.of("ARP-IIIIIIII-00000000", "ARP-OOOOOOOO-00000000",
                    "ARP-LLLLLLLL-00000000", "ARP-UUUUUUUU-00000000",
                    "ARP-0000000-00000000", "ARP0000000000000000", "someone@bu.edu")) {
                assertThat(ArchivePrincipalId.isArchiveIssued(slip))
                        .as("%s must not be accepted", slip)
                        .isFalse();
                assertThatThrownBy(() -> ArchivePrincipalId.parse(slip))
                        .isInstanceOf(IllegalArgumentException.class);
            }
        }

        @Test
        void caseAndSpacingAreNormalisedButNothingElseIsRepaired() {
            InstitutionalIdentifier minted = ArchivePrincipalId.mint();

            assertThat(ArchivePrincipalId.parse("  " + minted.value().toLowerCase() + " "))
                    .isEqualTo(minted);
        }
    }

    @Nested
    @DisplayName("Identities cannot be reassigned")
    class CannotBeReassigned {

        @Test
        void aSecondCognitoIdentityCannotTakeAnArchiveIdentityThatIsAlreadyLinked() {
            InstitutionalIdentifier archiveId = ArchivePrincipalId.mint();
            link("cognito-person-a", archiveId);

            assertThatThrownBy(() -> link("cognito-person-b", archiveId))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("already linked to a different Cognito identity");

            assertThat(activeLinksFor(archiveId)).isEqualTo(1);
        }

        @Test
        void theDatabaseHoldsTheInvariantEvenIfTheServiceCheckIsBypassed() {
            /*
             * The application check is a courtesy; V085's partial unique index
             * is the guarantee. Insert straight into the table to prove the
             * constraint, not the Java.
             */
            InstitutionalIdentifier archiveId = ArchivePrincipalId.mint();
            link("cognito-person-a", archiveId);

            assertThatThrownBy(() -> jdbc.sql("""
                            INSERT INTO authz.identity_link
                                (cognito_issuer, cognito_subject, institutional_identifier,
                                 method, status, verified_by, verified_at)
                            VALUES (:issuer, 'cognito-person-b', :identity,
                                    'ADMIN_VERIFIED', 'ACTIVE', 'test', CURRENT_TIMESTAMP)
                            """)
                    .param("issuer", ISSUER)
                    .param("identity", archiveId.value())
                    .update())
                    .hasMessageContaining("ux_identity_link_active_identity");
        }

        @Test
        void anIdentityWithPriorHistoryCannotBeLinkedByTheOrdinaryPath() {
            /*
             * The hole V085 does NOT close, found by probing this design: a
             * revoked link leaves the archive identity and its grants intact,
             * so linking a DIFFERENT person to it transfers them silently.
             * The partial index does not apply, because the old row is
             * REVOKED.
             *
             * link() therefore refuses any identity with prior history. Only
             * relink() - a named, separately audited act - accepts one.
             */
            InstitutionalIdentifier archiveId = ArchivePrincipalId.mint();
            var first = link("cognito-person-a", archiveId);
            grantCentralTo(archiveId);
            inTransaction.executeWithoutResult(s -> identities.revokeLink(first, "operator"));

            assertThatThrownBy(() -> link("cognito-person-b", archiveId))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("has been linked before")
                    .hasMessageContaining("would transfer them");

            assertThat(activeLinksFor(archiveId)).isZero();
        }

        @Test
        void relinkingIsTheExplicitPathForAReplacedCognitoPool() {
            // Same person, new pool. Grants are untouched because they key on
            // the archive identity, not on the Cognito subject.
            InstitutionalIdentifier archiveId = ArchivePrincipalId.mint();
            var first = link("cognito-old-pool", archiveId);
            grantCentralTo(archiveId);
            inTransaction.executeWithoutResult(s -> identities.revokeLink(first, "operator"));

            assertThatCode(() -> inTransaction.executeWithoutResult(s -> identities.relink(
                    ISSUER, "cognito-new-pool", archiveId, null, "display-only", "operator")))
                    .doesNotThrowAnyException();

            assertThat(activeLinksFor(archiveId)).isEqualTo(1);
            assertThat(mappedIdentifier("cognito-new-pool")).isEqualTo(archiveId.value());
            assertThat(jdbc.sql("""
                            SELECT COUNT(*) FROM authz.identity_link
                            WHERE institutional_identifier = :id
                            """).param("id", archiveId.value()).query(Integer.class).single())
                    .as("the revoked link stays as history")
                    .isEqualTo(2);
            assertThat(jdbc.sql("""
                            SELECT COUNT(*) FROM authz.access_grant
                            WHERE institutional_identifier = :id AND revoked_at IS NULL
                            """).param("id", archiveId.value()).query(Integer.class).single())
                    .as("zero re-grants needed after a pool replacement")
                    .isEqualTo(1);
        }

        @Test
        void relinkingRefusesAnIdentityThatHasNeverBeenLinked() {
            assertThatThrownBy(() -> inTransaction.executeWithoutResult(s -> identities.relink(
                    ISSUER, "cognito-person-a", ArchivePrincipalId.mint(), null, null, "operator")))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("never been linked");
        }

        @Test
        void relinkingStillCannotProduceTwoActiveLinks() {
            InstitutionalIdentifier archiveId = ArchivePrincipalId.mint();
            link("cognito-person-a", archiveId);

            assertThatThrownBy(() -> inTransaction.executeWithoutResult(s -> identities.relink(
                    ISSUER, "cognito-person-b", archiveId, null, null, "operator")))
                    .hasMessageContaining("ux_identity_link_active_identity");
        }

        @Test
        void oneCognitoIdentityStillCannotHoldTwoArchiveIdentities() {
            link("cognito-person-a", ArchivePrincipalId.mint());

            assertThatThrownBy(() -> link("cognito-person-a", ArchivePrincipalId.mint()))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("active identity link already exists");
        }
    }

    @Nested
    @DisplayName("Nobody inherits another person's grants")
    class NoInheritance {

        @Test
        void aMisLinkedSecondPersonCannotReachTheFirstPersonsGrants() {
            /*
             * The regression this whole design turns on. Before V085, person B
             * could be linked to person A's identity and would resolve to it,
             * inheriting every grant A held - IdentityResolver could not see
             * it, because its ambiguity check filters kuali_person_id IS NOT
             * NULL and an administrator-verified link has none.
             */
            InstitutionalIdentifier archiveId = ArchivePrincipalId.mint();
            link("cognito-person-a", archiveId);
            grantCentralTo(archiveId);

            assertThatThrownBy(() -> link("cognito-person-b", archiveId))
                    .isInstanceOf(IllegalStateException.class);

            IdentityResolution b = resolver.resolve(cognito("cognito-person-b"));
            assertThat(b)
                    .as("person B resolves to nothing, so there is nothing to inherit")
                    .isInstanceOf(IdentityResolution.NotProvisioned.class);
        }

        @Test
        void eachPersonResolvesOnlyToTheirOwnArchiveIdentity() {
            InstitutionalIdentifier a = ArchivePrincipalId.mint();
            InstitutionalIdentifier b = ArchivePrincipalId.mint();
            link("cognito-person-a", a);
            link("cognito-person-b", b);

            assertThat(mappedIdentifier("cognito-person-a")).isEqualTo(a.value());
            assertThat(mappedIdentifier("cognito-person-b")).isEqualTo(b.value());
        }

        @Test
        void revokingAPersonsLinkDoesNotMoveTheirGrantsToAnyoneElse() {
            InstitutionalIdentifier a = ArchivePrincipalId.mint();
            var link = link("cognito-person-a", a);
            grantCentralTo(a);

            inTransaction.executeWithoutResult(s -> identities.revokeLink(link, "operator"));

            assertThat(resolver.resolve(cognito("cognito-person-a")))
                    .isInstanceOf(IdentityResolution.Revoked.class);
            assertThat(jdbc.sql("""
                            SELECT COUNT(*) FROM authz.access_grant
                            WHERE institutional_identifier = :id AND revoked_at IS NULL
                            """).param("id", a.value()).query(Integer.class).single())
                    .as("the grant stays with the archive identity, reachable again "
                            + "only by re-linking - it does not follow a person or lapse")
                    .isEqualTo(1);
        }

        @Test
        void anUnlinkedCognitoIdentityResolvesToNothingHowEverManyGrantsExist() {
            grantCentralTo(ArchivePrincipalId.mint());

            assertThat(resolver.resolve(cognito("cognito-stranger")))
                    .isInstanceOf(IdentityResolution.NotProvisioned.class);
        }
    }

    @Test
    void theKualiCrosswalkSurvivesAndIsNeverTheGrantKey() {
        // Requirement: historical research relationships keep their KRIM
        // principal, while access keys on the archive identity.
        InstitutionalIdentifier archiveId = ArchivePrincipalId.mint();
        inTransaction.executeWithoutResult(s -> identities.link(
                ISSUER, "cognito-person-a", archiveId,
                new edu.bu.archive.application.authorization.KualiPersonId("KRIM-12345"),
                "display-only", "operator"));

        assertThat(jdbc.sql("""
                        SELECT kuali_person_id FROM authz.identity_link
                        WHERE institutional_identifier = :id AND status = 'ACTIVE'
                        """).param("id", archiveId.value()).query(String.class).single())
                .as("recorded for historical relationships")
                .isEqualTo("KRIM-12345");

        grantCentralTo(archiveId);
        assertThat(jdbc.sql("""
                        SELECT institutional_identifier FROM authz.access_grant
                        """).query(String.class).single())
                .as("but the grant keys on the archive identity, never on the principal")
                .isEqualTo(archiveId.value());
    }


    // ------------------------------------------------------------------

    private static long link(String subject, InstitutionalIdentifier identity) {
        return inTransaction.execute(s -> identities.link(
                ISSUER, subject, identity, null, "display-only", "operator").id());
    }

    private static void grantCentralTo(InstitutionalIdentifier identity) {
        jdbc.sql("""
                        INSERT INTO authz.access_grant
                            (institutional_identifier, grant_type, granted_by)
                        VALUES (:id, 'CENTRAL', 'test')
                        """)
                .param("id", identity.value())
                .update();
    }

    private static int activeLinksFor(InstitutionalIdentifier identity) {
        return jdbc.sql("""
                        SELECT COUNT(*) FROM authz.identity_link
                        WHERE institutional_identifier = :id AND status = 'ACTIVE'
                        """)
                .param("id", identity.value())
                .query(Integer.class)
                .single();
    }

    private static String mappedIdentifier(String subject) {
        IdentityResolution resolution = resolver.resolve(cognito(subject));
        assertThat(resolution).isInstanceOf(IdentityResolution.Mapped.class);
        return ((IdentityResolution.Mapped) resolution).institutionalIdentifier().value();
    }

    private static ValidatedCognitoIdentity cognito(String subject) {
        return new ValidatedCognitoIdentity(ISSUER, subject);
    }

    private static int migrationVersion(Path path) {
        Matcher matcher = MIGRATION_VERSION.matcher(path.getFileName().toString());
        return matcher.matches() ? Integer.parseInt(matcher.group(1)) : Integer.MAX_VALUE;
    }

    private static Path locateMigrationsDirectory() {
        Path candidate = Path.of("database", "migrations");
        return Files.isDirectory(candidate) ? candidate : Path.of("..", "database", "migrations");
    }
}
