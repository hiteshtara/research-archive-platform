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
import edu.bu.archive.application.authorization.admin.RelinkApproval;
import edu.bu.archive.application.authorization.admin.RelinkApprovalRequiredException;

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
    private static edu.bu.archive.application.authorization.admin.RelinkApprovalRepository approvals;
    private static edu.bu.archive.application.authorization.admin.ArchiveAdminService service;
    private static final InstitutionalIdentifier ADMIN_ONE =
            new InstitutionalIdentifier("ARP-ADMIN001-00000000");
    private static final InstitutionalIdentifier ADMIN_TWO =
            new InstitutionalIdentifier("ARP-ADMIN002-00000000");
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

        approvals = transactionalPort(new JdbcRelinkApprovalRepository(jdbc),
                edu.bu.archive.application.authorization.admin.RelinkApprovalRepository.class);

        var serviceTarget = new edu.bu.archive.application.authorization.admin.ArchiveAdminService(
                transactionalPort(new JdbcAdminRoleRepository(jdbc),
                        edu.bu.archive.application.authorization.admin.AdminRoleRepository.class),
                transactionalPort(new JdbcAdminAuditSink(jdbc),
                        edu.bu.archive.application.authorization.admin.AdminAuditSink.class),
                transactionalPort(new JdbcAccessGrantRepository(jdbc),
                        edu.bu.archive.application.authorization.AccessGrantRepository.class),
                identities,
                new JdbcAdminAuditRepository(jdbc),
                approvals);
        var serviceFactory = new ProxyFactory(serviceTarget);
        serviceFactory.setProxyTargetClass(true);
        serviceFactory.addAdvice(new TransactionInterceptor(
                txManager, new AnnotationTransactionAttributeSource()));
        service = (edu.bu.archive.application.authorization.admin.ArchiveAdminService)
                serviceFactory.getProxy();

        resolver = new IdentityResolver(new JdbcIdentityLinkRepository(jdbc));
    }

    private static <T> T transactionalPort(Object target, Class<T> port) {
        var factory = new ProxyFactory(target);
        factory.setInterfaces(port);
        factory.addAdvice(new TransactionInterceptor(
                txManager, new AnnotationTransactionAttributeSource()));
        return port.cast(factory.getProxy());
    }

    @BeforeEach
    void reset() {
        jdbc.sql("DELETE FROM authz.relink_approval").update();
        jdbc.sql("DELETE FROM authz.admin_role_member").update();
        jdbc.sql("DELETE FROM authz.access_grant").update();
        jdbc.sql("DELETE FROM authz.identity_link").update();
        jdbc.sql("DELETE FROM authz.person_status").update();
        jdbc.sql("TRUNCATE authz.access_audit").update();
        // Two administrators exist in every approval test; the two-person
        // rule is meaningless with one.
        inTransaction.executeWithoutResult(s ->
                service.bootstrapFirstAdministrator(ADMIN_ONE, "operator", "test"));
        inTransaction.executeWithoutResult(s ->
                service.assignAdministrator(ADMIN_ONE, ADMIN_TWO, "second administrator"));
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
    void v085FailsSafelyWhenDuplicateActiveLinksAlreadyExist() {
        /*
         * The migration must refuse rather than silently index over a
         * violation - that would mean two people already share one archive
         * identity, which has to be resolved deliberately. Drop the index,
         * create the state it forbids, then try to put it back.
         */
        InstitutionalIdentifier id = ArchivePrincipalId.mint();
        jdbc.sql("DROP INDEX authz.ux_identity_link_active_identity").update();
        try {
            for (String subject : List.of("cognito-dup-a", "cognito-dup-b")) {
                jdbc.sql("""
                        INSERT INTO authz.identity_link
                            (cognito_issuer, cognito_subject, institutional_identifier,
                             method, status, verified_by, verified_at)
                        VALUES (:issuer, :subject, :identity, 'ADMIN_VERIFIED', 'ACTIVE',
                                'test', CURRENT_TIMESTAMP)
                        """)
                        .param("issuer", ISSUER).param("subject", subject)
                        .param("identity", id.value())
                        .update();
            }

            assertThatThrownBy(() -> jdbc.sql("""
                            CREATE UNIQUE INDEX ux_identity_link_active_identity
                                ON authz.identity_link (institutional_identifier)
                                WHERE status = 'ACTIVE'
                            """).update())
                    .as("the migration fails loudly rather than accepting the violation")
                    .hasMessageContaining("could not create unique index");
        } finally {
            jdbc.sql("DELETE FROM authz.identity_link").update();
            jdbc.sql("""
                    CREATE UNIQUE INDEX IF NOT EXISTS ux_identity_link_active_identity
                        ON authz.identity_link (institutional_identifier)
                        WHERE status = 'ACTIVE'
                    """).update();
        }
    }

    @Nested
    @DisplayName("Two-person rule for re-linking an identity that holds grants")
    class TwoPersonRule {

        @Test
        void relinkingAnIdentityWithGrantsIsRefusedWithoutASecondApproval() {
            InstitutionalIdentifier id = linkedAndGrantedThenRevoked();

            assertThatThrownBy(() -> inTransaction.executeWithoutResult(s ->
                    service.relinkIdentity(ADMIN_ONE, ISSUER, "cognito-new", id,
                            null, null, "pool replaced")))
                    .isInstanceOf(RelinkApprovalRequiredException.class)
                    .hasMessageContaining("second, distinct ARCHIVE_ADMIN");

            assertThat(activeLinksFor(id)).isZero();
        }

        @Test
        void relinkingAnIdentityWithNoGrantsKeepsTheSingleAdministratorProcedure() {
            InstitutionalIdentifier id = ArchivePrincipalId.mint();
            long first = link("cognito-old", id);
            inTransaction.executeWithoutResult(s -> identities.revokeLink(first, "operator"));

            assertThatCode(() -> inTransaction.executeWithoutResult(s ->
                    service.relinkIdentity(ADMIN_ONE, ISSUER, "cognito-new", id,
                            null, null, "pool replaced")))
                    .doesNotThrowAnyException();
            assertThat(activeLinksFor(id)).isEqualTo(1);
        }

        @Test
        void aSecondDistinctAdministratorCanApproveAndThenTheRelinkSucceeds() {
            InstitutionalIdentifier id = linkedAndGrantedThenRevoked();

            long approvalId = inTransaction.execute(s -> service.requestRelinkApproval(
                    ADMIN_ONE, id, ISSUER, "cognito-new", "pool replaced").approvalId());
            inTransaction.executeWithoutResult(s -> service.approveRelink(ADMIN_TWO, approvalId));

            assertThatCode(() -> inTransaction.executeWithoutResult(s ->
                    service.relinkIdentity(ADMIN_ONE, ISSUER, "cognito-new", id,
                            null, null, "pool replaced")))
                    .doesNotThrowAnyException();

            assertThat(mappedIdentifier("cognito-new")).isEqualTo(id.value());
            assertThat(approvals.findById(approvalId).orElseThrow().status())
                    .isEqualTo(RelinkApproval.Status.CONSUMED);
        }

        @Test
        void theRequesterCannotApproveTheirOwnRequest() {
            InstitutionalIdentifier id = linkedAndGrantedThenRevoked();
            long approvalId = inTransaction.execute(s -> service.requestRelinkApproval(
                    ADMIN_ONE, id, ISSUER, "cognito-new", "pool replaced").approvalId());

            assertThatThrownBy(() -> inTransaction.executeWithoutResult(s ->
                    service.approveRelink(ADMIN_ONE, approvalId)))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("cannot also approve");
        }

        @Test
        void theDatabaseRefusesASelfApprovalEvenIfTheServiceCheckIsBypassed() {
            InstitutionalIdentifier id = linkedAndGrantedThenRevoked();
            long approvalId = inTransaction.execute(s -> service.requestRelinkApproval(
                    ADMIN_ONE, id, ISSUER, "cognito-new", "pool replaced").approvalId());

            assertThatThrownBy(() -> jdbc.sql("""
                            UPDATE authz.relink_approval
                               SET status='APPROVED', approved_by = requested_by,
                                   approved_at = CURRENT_TIMESTAMP
                             WHERE approval_id = :id
                            """).param("id", approvalId).update())
                    .hasMessageContaining("ck_relink_approval_two_people");
        }

        @Test
        void theApproverCannotBeTheCognitoIdentityBeingLinked() {
            // One person on both sides: approving a relink onto their own
            // Cognito account would hand them the identity's grants.
            InstitutionalIdentifier id = linkedAndGrantedThenRevoked();
            link("cognito-admin-two", ADMIN_TWO);

            long approvalId = inTransaction.execute(s -> service.requestRelinkApproval(
                    ADMIN_ONE, id, ISSUER, "cognito-admin-two", "suspicious").approvalId());

            assertThatThrownBy(() -> inTransaction.executeWithoutResult(s ->
                    service.approveRelink(ADMIN_TWO, approvalId)))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("both sides of the decision");
        }

        @Test
        void anApprovalCannotBeReusedForAnotherIdentityOrTarget() {
            InstitutionalIdentifier id = linkedAndGrantedThenRevoked();
            InstitutionalIdentifier other = linkedAndGrantedThenRevoked("cognito-other-old");

            long approvalId = inTransaction.execute(s -> service.requestRelinkApproval(
                    ADMIN_ONE, id, ISSUER, "cognito-new", "pool replaced").approvalId());
            inTransaction.executeWithoutResult(s -> service.approveRelink(ADMIN_TWO, approvalId));

            // A different TARGET for the same identity.
            assertThatThrownBy(() -> inTransaction.executeWithoutResult(s ->
                    service.relinkIdentity(ADMIN_ONE, ISSUER, "cognito-somewhere-else", id,
                            null, null, "nope")))
                    .isInstanceOf(RelinkApprovalRequiredException.class);
            // A different IDENTITY with the same target.
            assertThatThrownBy(() -> inTransaction.executeWithoutResult(s ->
                    service.relinkIdentity(ADMIN_ONE, ISSUER, "cognito-new", other,
                            null, null, "nope")))
                    .isInstanceOf(RelinkApprovalRequiredException.class);
        }

        @Test
        void anApprovalIsConsumedAndCannotAuthoriseASecondRelink() {
            InstitutionalIdentifier id = linkedAndGrantedThenRevoked();
            long approvalId = inTransaction.execute(s -> service.requestRelinkApproval(
                    ADMIN_ONE, id, ISSUER, "cognito-new", "pool replaced").approvalId());
            inTransaction.executeWithoutResult(s -> service.approveRelink(ADMIN_TWO, approvalId));
            inTransaction.executeWithoutResult(s -> service.relinkIdentity(
                    ADMIN_ONE, ISSUER, "cognito-new", id, null, null, "pool replaced"));

            long newest = jdbc.sql("""
                    SELECT identity_link_id FROM authz.identity_link
                    WHERE institutional_identifier = :id AND status='ACTIVE'
                    """).param("id", id.value()).query(Long.class).single();
            inTransaction.executeWithoutResult(s -> identities.revokeLink(newest, "operator"));

            assertThatThrownBy(() -> inTransaction.executeWithoutResult(s ->
                    service.relinkIdentity(ADMIN_ONE, ISSUER, "cognito-new", id,
                            null, null, "again")))
                    .isInstanceOf(RelinkApprovalRequiredException.class);
        }

        @Test
        void aRevokedApprovalAuthorisesNothing() {
            InstitutionalIdentifier id = linkedAndGrantedThenRevoked();
            long approvalId = inTransaction.execute(s -> service.requestRelinkApproval(
                    ADMIN_ONE, id, ISSUER, "cognito-new", "pool replaced").approvalId());
            inTransaction.executeWithoutResult(s -> service.approveRelink(ADMIN_TWO, approvalId));
            inTransaction.executeWithoutResult(s ->
                    service.revokeRelinkApproval(ADMIN_TWO, approvalId));

            assertThatThrownBy(() -> inTransaction.executeWithoutResult(s ->
                    service.relinkIdentity(ADMIN_ONE, ISSUER, "cognito-new", id,
                            null, null, "pool replaced")))
                    .isInstanceOf(RelinkApprovalRequiredException.class);
            assertThat(activeLinksFor(id)).isZero();
        }

        @Test
        void aGrantIssuedAfterApprovalIsStillCoveredBecauseGrantsAreRecheckedAtExecution() {
            /*
             * The recheck matters in both directions. Here the identity had
             * NO grants when the relink was first considered, so no approval
             * was needed - and a grant appears before execution. The count is
             * taken at execution, so the approval requirement appears with it
             * rather than being decided earlier and going stale.
             */
            InstitutionalIdentifier id = ArchivePrincipalId.mint();
            long first = link("cognito-old", id);
            inTransaction.executeWithoutResult(s -> identities.revokeLink(first, "operator"));

            grantCentralTo(id);

            assertThatThrownBy(() -> inTransaction.executeWithoutResult(s ->
                    service.relinkIdentity(ADMIN_ONE, ISSUER, "cognito-new", id,
                            null, null, "pool replaced")))
                    .isInstanceOf(RelinkApprovalRequiredException.class);
        }

        @Test
        void concurrentRelinksCannotBothConsumeOneApproval() throws Exception {
            InstitutionalIdentifier id = linkedAndGrantedThenRevoked();
            long approvalId = inTransaction.execute(s -> service.requestRelinkApproval(
                    ADMIN_ONE, id, ISSUER, "cognito-new", "pool replaced").approvalId());
            inTransaction.executeWithoutResult(s -> service.approveRelink(ADMIN_TWO, approvalId));

            int threads = 4;
            var barrier = new java.util.concurrent.CyclicBarrier(threads);
            var pool = java.util.concurrent.Executors.newFixedThreadPool(threads);
            try {
                var attempts = java.util.stream.IntStream.range(0, threads)
                        .<java.util.concurrent.Callable<String>>mapToObj(i -> () -> {
                            barrier.await(10, java.util.concurrent.TimeUnit.SECONDS);
                            try {
                                inTransaction.executeWithoutResult(s -> service.relinkIdentity(
                                        ADMIN_ONE, ISSUER, "cognito-new", id, null, null, "race"));
                                return "OK";
                            } catch (RuntimeException refused) {
                                return "REFUSED";
                            }
                        })
                        .toList();
                for (var f : pool.invokeAll(attempts)) {
                    f.get(30, java.util.concurrent.TimeUnit.SECONDS);
                }
            } finally {
                pool.shutdownNow();
            }

            assertThat(activeLinksFor(id))
                    .as("one approval authorises one relink, however the attempts interleave")
                    .isEqualTo(1);
            assertThat(approvals.findById(approvalId).orElseThrow().status())
                    .isEqualTo(RelinkApproval.Status.CONSUMED);
        }

        @Test
        void theRequestApprovalAndRelinkAreAllAudited() {
            InstitutionalIdentifier id = linkedAndGrantedThenRevoked();
            long approvalId = inTransaction.execute(s -> service.requestRelinkApproval(
                    ADMIN_ONE, id, ISSUER, "cognito-new", "pool replaced").approvalId());
            inTransaction.executeWithoutResult(s -> service.approveRelink(ADMIN_TWO, approvalId));
            inTransaction.executeWithoutResult(s -> service.relinkIdentity(
                    ADMIN_ONE, ISSUER, "cognito-new", id, null, null, "pool replaced"));

            List<String> actions = jdbc.sql(
                    "SELECT action FROM authz.access_audit ORDER BY audit_id")
                    .query(String.class).list();
            assertThat(actions).containsSubsequence(
                    "RELINK_APPROVAL_REQUESTED", "RELINK_APPROVAL_GRANTED", "IDENTITY_RELINKED");
            assertThat(jdbc.sql("""
                    SELECT detail::text FROM authz.access_audit
                    WHERE action='IDENTITY_RELINKED'
                    """).query(String.class).single())
                    .as("the audit says how much access moved, and under which approval")
                    .contains("grants inherited by the new Cognito identity=1")
                    .contains("approval=" + approvalId);
        }

        @Test
        void theFirstAdministratorBootstrapIsUnaffected() {
            // Bootstrap links a freshly minted identity with no history and
            // no grants, so it never reaches the approval path.
            jdbc.sql("DELETE FROM authz.admin_role_member").update();
            jdbc.sql("TRUNCATE authz.access_audit").update();
            InstitutionalIdentifier fresh = ArchivePrincipalId.mint();

            assertThatCode(() -> inTransaction.executeWithoutResult(s -> {
                service.bootstrapFirstAdministrator(fresh, "operator", "bootstrap");
                service.linkIdentity(fresh, ISSUER, "cognito-first-admin", fresh,
                        null, null, "bootstrap");
            })).doesNotThrowAnyException();

            assertThat(jdbc.sql("SELECT COUNT(*) FROM authz.relink_approval")
                    .query(Integer.class).single())
                    .as("no approval machinery is touched")
                    .isZero();
        }

        private InstitutionalIdentifier linkedAndGrantedThenRevoked() {
            return linkedAndGrantedThenRevoked("cognito-old");
        }

        private InstitutionalIdentifier linkedAndGrantedThenRevoked(String oldSubject) {
            InstitutionalIdentifier id = ArchivePrincipalId.mint();
            long first = link(oldSubject, id);
            grantCentralTo(id);
            inTransaction.executeWithoutResult(s -> identities.revokeLink(first, "operator"));
            return id;
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
