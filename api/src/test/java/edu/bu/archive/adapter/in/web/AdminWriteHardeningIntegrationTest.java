package edu.bu.archive.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Statement;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.RequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.DefaultTransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import edu.bu.archive.adapter.out.persistence.authorization.JdbcAccessGrantRepository;
import edu.bu.archive.adapter.out.persistence.authorization.JdbcAdminAuditRepository;
import edu.bu.archive.adapter.out.persistence.authorization.JdbcAdminAuditSink;
import edu.bu.archive.adapter.out.persistence.authorization.JdbcAdminIdentityRepository;
import edu.bu.archive.adapter.out.persistence.authorization.JdbcAdminRoleRepository;
import edu.bu.archive.adapter.out.persistence.authorization.JdbcIdentityLinkRepository;
import edu.bu.archive.adapter.out.persistence.authorization.JdbcRelinkApprovalRepository;
import edu.bu.archive.application.authorization.AccessGrantRepository;
import edu.bu.archive.application.authorization.AuthorizationProperties;
import edu.bu.archive.application.authorization.CurrentIdentityProvider;
import edu.bu.archive.application.authorization.IdentityResolver;
import edu.bu.archive.application.authorization.InstitutionalIdentifier;
import edu.bu.archive.application.authorization.ValidatedCognitoIdentity;
import edu.bu.archive.application.authorization.admin.AdminAuditSink;
import edu.bu.archive.application.authorization.admin.AdminIdentityRepository;
import edu.bu.archive.application.authorization.admin.AdminRoleRepository;
import edu.bu.archive.application.authorization.admin.ArchiveAdminService;
import edu.bu.archive.application.authorization.admin.ReasonRequiredException;
import edu.bu.archive.application.authorization.admin.RelinkApprovalRepository;
import edu.bu.archive.application.authorization.admin.SelfAdministrationException;

/**
 * PR 1 of the A-category controls, end to end: HTTP through the real
 * controller and exception handler, the transactional service, and the real
 * schema in a disposable PostgreSQL.
 *
 * <p>Administrator writes are ENABLED here, deliberately: every refusal below
 * must come from the new controls, not from the read-only gate. Every refusal
 * is asserted to change no domain row and write no audit row; every success
 * writes exactly one audit row.
 *
 * <p>Synthetic identities only. Nothing here touches a deployed environment.
 */
@Testcontainers
class AdminWriteHardeningIntegrationTest {

    private static final Pattern MIGRATION_VERSION = Pattern.compile("^V(\\d+)__.*\\.sql$");
    private static final String ISSUER = "https://cognito-idp.test.invalid/us-east-1_SYNTHETIC";

    private static final String ADMIN = "SYN-HARD-ADMIN";
    private static final String ADMIN_B = "SYN-HARD-ADMIN-B";
    private static final String ALICE = "SYN-HARD-ALICE";
    private static final String BOB = "SYN-HARD-BOB";
    private static final String CAROL = "SYN-HARD-CAROL";
    private static final String REASON = "approved by the department chair";

    private static final List<String> TABLES = List.of(
            "access_grant", "identity_link", "admin_role_member", "person_status",
            "relink_approval", "access_audit");

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg17").asCompatibleSubstituteFor("postgres"));

    private static JdbcClient jdbc;
    private static PlatformTransactionManager txManager;
    private static ArchiveAdminService service;
    private static AccessGrantRepository grants;

    private AuthorizationProperties properties;
    private ValidatedCognitoIdentity caller;

    @BeforeAll
    static void applyMigrations() throws Exception {
        List<Path> migrations;
        try (Stream<Path> files = Files.list(locateMigrations())) {
            migrations = files
                    .filter(p -> MIGRATION_VERSION.matcher(p.getFileName().toString()).matches())
                    .sorted(Comparator.comparingInt(AdminWriteHardeningIntegrationTest::version))
                    .toList();
        }
        var dataSource = new SimpleDriverDataSource();
        dataSource.setDriverClass(org.postgresql.Driver.class);
        dataSource.setUrl(postgres.getJdbcUrl());
        dataSource.setUsername(postgres.getUsername());
        dataSource.setPassword(postgres.getPassword());
        try (Connection c = dataSource.getConnection(); Statement s = c.createStatement()) {
            for (Path m : migrations) {
                s.execute(Files.readString(m));
            }
        }
        jdbc = JdbcClient.create(dataSource);
        txManager = new DataSourceTransactionManager(dataSource);

        grants = port(new JdbcAccessGrantRepository(jdbc), AccessGrantRepository.class);
        var target = new ArchiveAdminService(
                port(new JdbcAdminRoleRepository(jdbc), AdminRoleRepository.class),
                port(new JdbcAdminAuditSink(jdbc), AdminAuditSink.class),
                grants,
                port(new JdbcAdminIdentityRepository(jdbc), AdminIdentityRepository.class),
                new JdbcAdminAuditRepository(jdbc),
                port(new JdbcRelinkApprovalRepository(jdbc), RelinkApprovalRepository.class));
        var factory = new ProxyFactory(target);
        factory.setProxyTargetClass(true);
        factory.addAdvice(new TransactionInterceptor(txManager, new AnnotationTransactionAttributeSource()));
        service = (ArchiveAdminService) factory.getProxy();
    }

    @BeforeEach
    void seed() {
        jdbc.sql("DELETE FROM authz.relink_approval").update();
        jdbc.sql("DELETE FROM authz.admin_role_member").update();
        jdbc.sql("DELETE FROM authz.access_grant").update();
        jdbc.sql("DELETE FROM authz.identity_link").update();
        jdbc.sql("DELETE FROM authz.person_status").update();
        jdbc.sql("TRUNCATE authz.access_audit").update();

        for (String who : List.of(ADMIN, ADMIN_B, ALICE, BOB)) {
            jdbc.sql("""
                    INSERT INTO authz.identity_link (cognito_issuer, cognito_subject,
                        institutional_identifier, method, status, verified_by, verified_at)
                    VALUES (:iss, :sub, :who, 'ADMIN_VERIFIED', 'ACTIVE', 'operator', now())
                    """).param("iss", ISSUER).param("sub", subjectOf(who)).param("who", who).update();
        }
        for (String who : List.of(ADMIN, ADMIN_B)) {
            jdbc.sql("""
                    INSERT INTO authz.admin_role_member (institutional_identifier, status,
                        granted_by, granted_at, reason)
                    VALUES (:who, 'ACTIVE', 'BOOTSTRAP', now(), 'synthetic fixture')
                    """).param("who", who).update();
        }

        properties = new AuthorizationProperties();
        properties.setEnforcementEnabled(false);
        properties.setAdministrationEnabled(true);
        properties.setAdministrationWritesEnabled(true);
        caller = new ValidatedCognitoIdentity(ISSUER, subjectOf(ADMIN));
    }

    // ---- control 1: no self-grant -------------------------------------

    @Test
    void selfGrantOverHttpIs403WithNoGrantAndNoAuditRow() throws Exception {
        expectUnchanged(post("/api/v1/admin/identities/" + ADMIN + "/grants")
                        .contentType("application/json")
                        .content(grantBody("UNIT", "1234", null, REASON)),
                403, "SELF_ADMINISTRATION_REQUIRES_APPROVAL");
    }

    // ---- decision D1: CENTRAL and appointment need approval ------------

    @Test
    void centralGrantIs403ApprovalRequiredEvenWithWritesEnabled() throws Exception {
        assertThat(properties.isAdministrationWritesEnabled()).isTrue();
        expectUnchanged(post("/api/v1/admin/identities/" + ALICE + "/grants")
                        .contentType("application/json")
                        .content(grantBody("CENTRAL", null, null, REASON)),
                403, "APPROVAL_REQUIRED");
    }

    @Test
    void assigningAnAdministratorIs403ApprovalRequired() throws Exception {
        expectUnchanged(post("/api/v1/admin/administrators")
                        .contentType("application/json")
                        .content("{\"institutionalIdentifier\":\"" + ALICE + "\",\"reason\":\""
                                + REASON + "\"}"),
                403, "APPROVAL_REQUIRED");
    }

    // ---- N4: awardNumber reaches the grant -----------------------------

    @Test
    void aParentAwardGrantOverHttpPersistsItsAwardNumber() throws Exception {
        mvc().perform(post("/api/v1/admin/identities/" + ALICE + "/grants")
                        .contentType("application/json")
                        .content(grantBody("PARENT_AWARD", null, "100380-00001", REASON)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.awardNumber").value("100380-00001"));

        assertThat(jdbc.sql("""
                        SELECT award_number FROM authz.access_grant
                         WHERE institutional_identifier = :g AND grant_type = 'PARENT_AWARD'
                        """).param("g", ALICE).query(String.class).single())
                .isEqualTo("100380-00001");
        assertThat(count("access_audit")).isEqualTo(1);
    }

    @Test
    void anAwardNumberOnAUnitGrantIsRefused() throws Exception {
        expectUnchanged(post("/api/v1/admin/identities/" + ALICE + "/grants")
                        .contentType("application/json")
                        .content(grantBody("UNIT", "1234", "100380-00001", REASON)),
                400, "BAD_REQUEST");
    }

    // ---- N1: the path identity owns the grant or link ------------------

    @Test
    void patchingAnotherIdentitysGrantIs404AndLooksLikeAMissingOne() throws Exception {
        long bobs = seedGrant(BOB, false, null, "original justification for Bob");

        String foreign = expectUnchanged(patch("/api/v1/admin/identities/" + ALICE + "/grants/" + bobs)
                        .contentType("application/json")
                        .content("{\"includeDescendants\":true,\"reason\":\"" + REASON + "\"}"),
                404, "NOT_FOUND");
        String missing = expectUnchanged(patch("/api/v1/admin/identities/" + ALICE + "/grants/" + (bobs + 9999))
                        .contentType("application/json")
                        .content("{\"includeDescendants\":true,\"reason\":\"" + REASON + "\"}"),
                404, "NOT_FOUND");
        assertThat(foreign.replace(String.valueOf(bobs), "#"))
                .isEqualTo(missing.replace(String.valueOf(bobs + 9999), "#"));
    }

    @Test
    void revokingAnotherIdentitysGrantIs404AndLeavesItActive() throws Exception {
        long bobs = seedGrant(BOB, false, null, "original justification for Bob");
        expectUnchanged(delete("/api/v1/admin/identities/" + ALICE + "/grants/" + bobs)
                .param("reason", REASON), 404, "NOT_FOUND");
        assertThat(activeGrantsFor(BOB)).isEqualTo(1);
    }

    @Test
    void unlinkingAnotherIdentitysLinkIs404AndLeavesItActive() throws Exception {
        long bobsLink = jdbc.sql("""
                SELECT identity_link_id FROM authz.identity_link WHERE institutional_identifier = :w
                """).param("w", BOB).query(Long.class).single();
        expectUnchanged(delete("/api/v1/admin/identities/" + ALICE + "/links/" + bobsLink)
                .param("reason", REASON), 404, "NOT_FOUND");
    }

    // ---- control 2: widening -------------------------------------------

    @Test
    void wideningYourOwnGrantIs403ForEachField() throws Exception {
        Instant soon = Instant.now().plus(10, ChronoUnit.DAYS).truncatedTo(ChronoUnit.SECONDS);
        long own = seedGrant(ADMIN, false, soon, "seeded own grant");
        Instant later = soon.plus(30, ChronoUnit.DAYS);

        for (String body : List.of(
                "{\"includeDescendants\":true,\"expiresAt\":\"" + soon + "\",\"reason\":\"" + REASON + "\"}",
                "{\"includeDescendants\":false,\"expiresAt\":\"" + later + "\",\"reason\":\"" + REASON + "\"}",
                "{\"includeDescendants\":false,\"reason\":\"" + REASON + "\"}")) {
            expectUnchanged(patch("/api/v1/admin/identities/" + ADMIN + "/grants/" + own)
                            .contentType("application/json").content(body),
                    403, "SELF_ADMINISTRATION_REQUIRES_APPROVAL");
        }
    }

    @Test
    void narrowingYourOwnGrantIsAllowedAndAuditedOnce() throws Exception {
        Instant later = Instant.now().plus(40, ChronoUnit.DAYS);
        Instant soon = Instant.now().plus(10, ChronoUnit.DAYS);
        long own = seedGrant(ADMIN, true, later, "seeded own grant");

        mvc().perform(patch("/api/v1/admin/identities/" + ADMIN + "/grants/" + own)
                        .contentType("application/json")
                        .content("{\"includeDescendants\":false,\"expiresAt\":\"" + soon
                                + "\",\"reason\":\"narrowing my own access\"}"))
                .andExpect(status().isOk());
        assertThat(count("access_audit")).isEqualTo(1);
    }

    @Test
    void wideningSomeoneElsesGrantIsAllowedAndTheAuditRowShowsBeforeAndAfter() throws Exception {
        Instant soon = Instant.now().plus(10, ChronoUnit.DAYS).truncatedTo(ChronoUnit.SECONDS);
        long alices = seedGrant(ALICE, false, soon, "seeded grant for Alice");

        mvc().perform(patch("/api/v1/admin/identities/" + ALICE + "/grants/" + alices)
                        .contentType("application/json")
                        .content("{\"includeDescendants\":true,\"reason\":\"extended for the audit\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.includeDescendants").value(true));

        String note = jdbc.sql("SELECT detail ->> 'note' FROM authz.access_audit")
                .query(String.class).single();
        assertThat(note)
                .contains("before={includeDescendants=false, expires=" + soon + "}")
                .contains("after={includeDescendants=true, expires=never}")
                .contains("extended for the audit");
    }

    @Test
    void anExpiryInThePastIs400() throws Exception {
        long alices = seedGrant(ALICE, false, null, "seeded grant for Alice");
        expectUnchanged(patch("/api/v1/admin/identities/" + ALICE + "/grants/" + alices)
                        .contentType("application/json")
                        .content("{\"includeDescendants\":false,\"expiresAt\":\""
                                + Instant.now().minus(1, ChronoUnit.DAYS) + "\",\"reason\":\""
                                + REASON + "\"}"),
                400, "BAD_REQUEST");
    }

    // ---- suspension ----------------------------------------------------

    @Test
    void suspendingYourselfIs403() throws Exception {
        expectUnchanged(put("/api/v1/admin/identities/" + ADMIN + "/suspension")
                        .contentType("application/json")
                        .content("{\"suspended\":true,\"reason\":\"" + REASON + "\"}"),
                403, "SELF_ADMINISTRATION_REQUIRES_APPROVAL");
    }

    @Test
    void restoringASuspendedAdministratorIs403ApprovalRequired() throws Exception {
        jdbc.sql("""
                INSERT INTO authz.person_status (institutional_identifier, suspended, changed_by, changed_at)
                VALUES (:w, TRUE, 'operator', now())
                """).param("w", ADMIN_B).update();
        expectUnchanged(put("/api/v1/admin/identities/" + ADMIN_B + "/suspension")
                        .contentType("application/json")
                        .content("{\"suspended\":false,\"reason\":\"" + REASON + "\"}"),
                403, "APPROVAL_REQUIRED");
    }

    // ---- QA N-161-1 / N-161-2 and suspension of an administrator -------

    @Test
    void suspendingAnotherAdministratorIs403ApprovalRequired() throws Exception {
        expectUnchanged(put("/api/v1/admin/identities/" + ADMIN_B + "/suspension")
                        .contentType("application/json")
                        .content("{\"suspended\":true,\"reason\":\"" + REASON + "\"}"),
                403, "APPROVAL_REQUIRED");
    }

    @Test
    void selfUnlinkIsRefused() throws Exception {
        expectUnchanged(delete("/api/v1/admin/identities/" + ADMIN + "/links/" + linkOf(ADMIN))
                .param("reason", REASON), 403, "SELF_ADMINISTRATION_REQUIRES_APPROVAL");
    }

    /**
     * The QA reproduction: one administrator unlinks another and relinks
     * that identity to an accomplice's Cognito subject. Before the fix the
     * accomplice resolved to ADMIN_B with no relink_approval row at all.
     */
    @Test
    void unlinkThenRelinkOfAnAdministratorIsRefused() throws Exception {
        mvc().perform(delete("/api/v1/admin/identities/" + ADMIN_B + "/links/" + linkOf(ADMIN_B))
                        .param("reason", "Cognito account compromised"))
                .andExpect(status().isNoContent());

        Map<String, String> before = snapshot();
        assertThatThrownBy(() -> service.relinkIdentity(id(ADMIN), ISSUER, "ACCOMPLICE-SUB",
                id(ADMIN_B), null, null, REASON))
                .isInstanceOf(edu.bu.archive.application.authorization.admin
                        .RelinkApprovalRequiredException.class);
        assertThat(snapshot()).isEqualTo(before);
        assertThat(new IdentityResolver(new JdbcIdentityLinkRepository(jdbc))
                .resolve(new ValidatedCognitoIdentity(ISSUER, "ACCOMPLICE-SUB")))
                .as("the accomplice resolves to nobody")
                .isNotInstanceOf(edu.bu.archive.application.authorization.IdentityResolution.Mapped.class);
        assertThat(count("relink_approval")).isZero();
    }

    @Test
    void relinkingAnAdministratorWithoutApprovalIsRefused() {
        jdbc.sql("""
                UPDATE authz.identity_link SET status = 'REVOKED', revoked_by = 'operator',
                       revoked_at = now() WHERE institutional_identifier = :w
                """).param("w", ADMIN_B).update();
        Map<String, String> before = snapshot();
        assertThatThrownBy(() -> service.relinkIdentity(id(ADMIN), ISSUER, "sub-new-b",
                id(ADMIN_B), null, null, REASON))
                .isInstanceOf(edu.bu.archive.application.authorization.admin
                        .RelinkApprovalRequiredException.class)
                .hasMessageContaining("ARCHIVE_ADMIN");
        assertThat(snapshot()).isEqualTo(before);
    }

    @Test
    void relinkCannotChangeTheKualiPersonId() {
        jdbc.sql("""
                INSERT INTO authz.identity_link (cognito_issuer, cognito_subject,
                    institutional_identifier, kuali_person_id, method, status, verified_by,
                    verified_at, revoked_by, revoked_at)
                VALUES (:iss, 'sub-carol-old', :w, 'P-CAROL', 'ADMIN_VERIFIED', 'REVOKED',
                        'operator', now(), 'operator', now())
                """).param("iss", ISSUER).param("w", CAROL).update();

        Map<String, String> before = snapshot();
        assertThatThrownBy(() -> service.relinkIdentity(id(ADMIN), ISSUER, "sub-carol-new",
                id(CAROL), new edu.bu.archive.application.authorization.KualiPersonId("VICTIM-PI"),
                null, REASON))
                .isInstanceOf(edu.bu.archive.application.authorization.admin
                        .ApprovalRequiredException.class)
                .hasMessageContaining("Kuali person id");
        assertThat(snapshot()).isEqualTo(before);

        // Non-vacuity: the same principal relinks (no grants, not an administrator).
        service.relinkIdentity(id(ADMIN), ISSUER, "sub-carol-new", id(CAROL),
                new edu.bu.archive.application.authorization.KualiPersonId("P-CAROL"), null, REASON);
        assertThat(jdbc.sql("""
                SELECT kuali_person_id FROM authz.identity_link
                 WHERE institutional_identifier = :w AND status = 'ACTIVE'
                """).param("w", CAROL).query(String.class).single()).isEqualTo("P-CAROL");
    }

    /**
     * Approve and revoke of one PENDING relink request, released together.
     * Whatever the interleaving, the row ends in one consistent state and
     * every successful transition has exactly one audit row.
     */
    @Test
    void approveVersusRevokeRaceLeavesAConsistentState() throws Exception {
        jdbc.sql("""
                UPDATE authz.identity_link SET status = 'REVOKED', revoked_by = 'operator',
                       revoked_at = now() WHERE institutional_identifier = :w
                """).param("w", BOB).update();
        long approvalId = service.requestRelinkApproval(id(ADMIN), id(BOB), ISSUER, "sub-bob-2",
                "Cognito pool replaced for Bob").approvalId();
        int auditBefore = count("access_audit");

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Throwable> approve = pool.submit(() -> {
                ready.countDown();
                go.await(20, TimeUnit.SECONDS);
                try {
                    service.approveRelink(id(ADMIN_B), approvalId, "verified the replacement pool");
                    return null;
                } catch (Throwable failure) {
                    return failure;
                }
            });
            Future<Throwable> revoke = pool.submit(() -> {
                ready.countDown();
                go.await(20, TimeUnit.SECONDS);
                try {
                    service.revokeRelinkApproval(id(ADMIN), approvalId, "request withdrawn after review");
                    return null;
                } catch (Throwable failure) {
                    return failure;
                }
            });
            assertThat(ready.await(20, TimeUnit.SECONDS)).isTrue();
            go.countDown();
            Throwable approveOutcome = approve.get(60, TimeUnit.SECONDS);
            Throwable revokeOutcome = revoke.get(60, TimeUnit.SECONDS);

            var row = jdbc.sql("""
                    SELECT status, approved_by IS NOT NULL AS approved,
                           revoked_at IS NOT NULL AS revoked, consumed_at IS NOT NULL AS consumed
                      FROM authz.relink_approval WHERE approval_id = :id
                    """).param("id", approvalId)
                    .query((rs, n) -> rs.getString("status") + "/" + rs.getBoolean("approved")
                            + "/" + rs.getBoolean("revoked") + "/" + rs.getBoolean("consumed"))
                    .single();
            assertThat(row).as("approve=%s revoke=%s", approveOutcome, revokeOutcome)
                    .isIn("REVOKED/true/true/false", "REVOKED/false/true/false",
                            "APPROVED/true/false/false");
            long successes = java.util.stream.Stream.of(approveOutcome, revokeOutcome)
                    .filter(java.util.Objects::isNull).count();
            assertThat(count("access_audit"))
                    .as("one audit row per successful transition")
                    .isEqualTo(auditBefore + (int) successes);
            assertThat(row.startsWith("REVOKED") || revokeOutcome != null).isTrue();
        } finally {
            pool.shutdownNow();
        }
    }

    /** The last-administrator guard still holds alongside the new refusals. */
    @Test
    void theLastAdministratorGuardStillHolds() throws Exception {
        mvc().perform(delete("/api/v1/admin/administrators/" + ADMIN_B)
                        .param("reason", "stepping down from the role"))
                .andExpect(status().isNoContent());
        expectUnchanged(delete("/api/v1/admin/administrators/" + ADMIN)
                        .param("reason", "stepping down from the role"),
                409, "LAST_ARCHIVE_ADMINISTRATOR");
    }

    @Test
    void aBulkGrantWhoseOnlyEntryIsTheActorWritesNothing() {
        Map<String, String> before = snapshot();
        var report = service.grantAccessInBulk(id(ADMIN), List.of(id(ADMIN)),
                new edu.bu.archive.application.authorization.admin.GrantSpecification(
                        edu.bu.archive.application.authorization.GrantType.UNIT, "1234",
                        false, null, null, REASON));
        assertThat(report.failed()).isEqualTo(1);
        assertThat(snapshot()).as("no grant and no batch summary row").isEqualTo(before);
    }

    // ---- N2: unlink then link ------------------------------------------

    @Test
    void linkingAnUnlinkedIdentityThatHoldsGrantsIs403() throws Exception {
        seedGrant(CAROL, false, null, "grant staged before linking");
        expectUnchanged(post("/api/v1/admin/identities/" + CAROL + "/links")
                        .contentType("application/json")
                        .content("{\"cognitoIssuer\":\"" + ISSUER + "\",\"cognitoSubject\":\"sub-carol-new\","
                                + "\"reason\":\"" + REASON + "\"}"),
                403, "APPROVAL_REQUIRED");
    }

    @Test
    void unlinkThenLinkOfAGrantHolderIsRefused() throws Exception {
        seedGrant(ALICE, false, null, "grant held by Alice");
        long link = jdbc.sql("""
                SELECT identity_link_id FROM authz.identity_link WHERE institutional_identifier = :w
                """).param("w", ALICE).query(Long.class).single();
        mvc().perform(delete("/api/v1/admin/identities/" + ALICE + "/links/" + link)
                        .param("reason", "Cognito account compromised"))
                .andExpect(status().isNoContent());

        expectUnchanged(post("/api/v1/admin/identities/" + ALICE + "/links")
                        .contentType("application/json")
                        .content("{\"cognitoIssuer\":\"" + ISSUER + "\",\"cognitoSubject\":\"sub-someone-else\","
                                + "\"reason\":\"" + REASON + "\"}"),
                403, "APPROVAL_REQUIRED");
    }

    // ---- N3: revocation keeps the grant's reason ------------------------

    @Test
    void revokingKeepsTheOriginalGrantReasonAndAuditsTheRevocationReason() throws Exception {
        long alices = seedGrant(ALICE, false, null, "original justification for Alice");
        mvc().perform(delete("/api/v1/admin/identities/" + ALICE + "/grants/" + alices)
                        .param("reason", "left the department in October"))
                .andExpect(status().isNoContent());

        assertThat(jdbc.sql("SELECT reason FROM authz.access_grant WHERE grant_id = :id")
                .param("id", alices).query(String.class).single())
                .isEqualTo("original justification for Alice");
        assertThat(jdbc.sql("SELECT detail ->> 'note' FROM authz.access_audit")
                .query(String.class).single())
                .contains("left the department in October");
    }

    // ---- decision D4: reasons ------------------------------------------

    @Test
    void anAbsentReasonQueryParameterIs400ReasonRequired() throws Exception {
        long alices = seedGrant(ALICE, false, null, "seeded grant for Alice");
        expectUnchanged(delete("/api/v1/admin/identities/" + ALICE + "/grants/" + alices),
                400, ReasonRequiredException.CODE);
        expectUnchanged(delete("/api/v1/admin/identities/" + ALICE + "/links/1"),
                400, ReasonRequiredException.CODE);
        expectUnchanged(delete("/api/v1/admin/administrators/" + ADMIN_B),
                400, ReasonRequiredException.CODE);
    }

    /** Blank, placeholder, 9 and 501 characters, over every HTTP write. */
    @ParameterizedTest(name = "reason=\"{0}\"")
    @ValueSource(strings = {"", "   ", "test", "N/A", "tbd - todo", "Left dept", "LONG"})
    void everyHttpWriteRefusesAnUnacceptableReasonAndWritesNothing(String raw) throws Exception {
        // "LONG" stands for 501 characters of otherwise acceptable text.
        String reason = "LONG".equals(raw)
                ? "Access approved by chair ".repeat(21).substring(0, 500) + "x"
                : raw;
        long alices = seedGrant(ALICE, false, null, "seeded grant for Alice");
        long bobsLink = jdbc.sql("""
                SELECT identity_link_id FROM authz.identity_link WHERE institutional_identifier = :w
                """).param("w", BOB).query(Long.class).single();
        jdbc.sql("""
                INSERT INTO authz.person_status (institutional_identifier, suspended, changed_by, changed_at)
                VALUES (:w, TRUE, 'operator', now())
                """).param("w", BOB).update();

        Map<String, RequestBuilder> writes = new LinkedHashMap<>();
        writes.put("grant", post("/api/v1/admin/identities/" + ALICE + "/grants")
                .contentType("application/json").content(grantBody("UNIT", "5555", null, reason)));
        writes.put("modify", patch("/api/v1/admin/identities/" + ALICE + "/grants/" + alices)
                .contentType("application/json")
                .content("{\"includeDescendants\":true,\"reason\":\"" + reason + "\"}"));
        writes.put("revoke grant", delete("/api/v1/admin/identities/" + ALICE + "/grants/" + alices)
                .param("reason", reason));
        writes.put("suspend", put("/api/v1/admin/identities/" + ALICE + "/suspension")
                .contentType("application/json")
                .content("{\"suspended\":true,\"reason\":\"" + reason + "\"}"));
        writes.put("restore", put("/api/v1/admin/identities/" + BOB + "/suspension")
                .contentType("application/json")
                .content("{\"suspended\":false,\"reason\":\"" + reason + "\"}"));
        writes.put("assign administrator", post("/api/v1/admin/administrators")
                .contentType("application/json")
                .content("{\"institutionalIdentifier\":\"" + ALICE + "\",\"reason\":\"" + reason + "\"}"));
        writes.put("revoke administrator", delete("/api/v1/admin/administrators/" + ADMIN_B)
                .param("reason", reason));
        writes.put("link", post("/api/v1/admin/identities/" + CAROL + "/links")
                .contentType("application/json")
                .content("{\"cognitoIssuer\":\"" + ISSUER + "\",\"cognitoSubject\":\"sub-carol\","
                        + "\"reason\":\"" + reason + "\"}"));
        writes.put("unlink", delete("/api/v1/admin/identities/" + BOB + "/links/" + bobsLink)
                .param("reason", reason));

        for (var write : writes.entrySet()) {
            Map<String, String> before = snapshot();
            var result = mvc().perform(write.getValue()).andReturn().getResponse();
            assertThat(result.getStatus()).as(write.getKey()).isEqualTo(400);
            assertThat(result.getContentAsString()).as(write.getKey())
                    .contains("\"code\":\"" + ReasonRequiredException.CODE + "\"");
            assertThat(snapshot()).as(write.getKey() + " changed the database").isEqualTo(before);
        }

        // The relink actions have no HTTP endpoint; same rule at the service.
        Map<String, Runnable> relink = new LinkedHashMap<>();
        relink.put("relinkIdentity", () -> service.relinkIdentity(id(ADMIN), ISSUER, "sub-bob-2",
                id(BOB), null, null, reason));
        relink.put("requestRelinkApproval", () -> service.requestRelinkApproval(id(ADMIN), id(BOB),
                ISSUER, "sub-bob-2", reason));
        relink.put("approveRelink", () -> service.approveRelink(id(ADMIN_B), 1L, reason));
        relink.put("revokeRelinkApproval", () -> service.revokeRelinkApproval(id(ADMIN_B), 1L, reason));
        for (var action : relink.entrySet()) {
            Map<String, String> before = snapshot();
            assertThatThrownBy(action.getValue()::run).as(action.getKey())
                    .isInstanceOf(ReasonRequiredException.class);
            assertThat(snapshot()).as(action.getKey() + " changed the database").isEqualTo(before);
        }
    }

    /** Non-vacuity for the table above: each write succeeds with a real reason. */
    @Test
    void theSameWritesSucceedWithARealReasonAndEachWritesOneAuditRow() throws Exception {
        long alices = seedGrant(ALICE, false, null, "seeded grant for Alice");
        long bobsLink = jdbc.sql("""
                SELECT identity_link_id FROM authz.identity_link WHERE institutional_identifier = :w
                """).param("w", BOB).query(Long.class).single();

        List<RequestBuilder> writes = new ArrayList<>(List.of(
                post("/api/v1/admin/identities/" + ALICE + "/grants")
                        .contentType("application/json").content(grantBody("UNIT", "5555", null, REASON)),
                patch("/api/v1/admin/identities/" + ALICE + "/grants/" + alices)
                        .contentType("application/json")
                        .content("{\"includeDescendants\":true,\"reason\":\"" + REASON + "\"}"),
                delete("/api/v1/admin/identities/" + ALICE + "/grants/" + alices).param("reason", REASON),
                put("/api/v1/admin/identities/" + ALICE + "/suspension")
                        .contentType("application/json")
                        .content("{\"suspended\":true,\"reason\":\"" + REASON + "\"}"),
                put("/api/v1/admin/identities/" + ALICE + "/suspension")
                        .contentType("application/json")
                        .content("{\"suspended\":false,\"reason\":\"" + REASON + "\"}"),
                delete("/api/v1/admin/administrators/" + ADMIN_B).param("reason", REASON),
                post("/api/v1/admin/identities/" + CAROL + "/links")
                        .contentType("application/json")
                        .content("{\"cognitoIssuer\":\"" + ISSUER + "\",\"cognitoSubject\":\"sub-carol\","
                                + "\"reason\":\"" + REASON + "\"}"),
                delete("/api/v1/admin/identities/" + BOB + "/links/" + bobsLink).param("reason", REASON)));

        for (RequestBuilder write : writes) {
            int before = count("access_audit");
            int status = mvc().perform(write).andReturn().getResponse().getStatus();
            assertThat(status).isBetween(200, 299);
            assertThat(count("access_audit")).as("exactly one audit row per success").isEqualTo(before + 1);
        }
    }

    // ---- N5: the widening decision uses the locked, committed value ----

    /**
     * Two transactions modify the same grant. T1 narrows the administrator's
     * own grant (expiry 60 days -> 10 days) and holds its transaction open.
     * T2 then sets the expiry to 30 days: against the stale value (60) that
     * is narrowing and would be allowed; against the committed value (10)
     * it is widening and must be refused. With {@code FOR UPDATE}, T2 blocks
     * until T1 commits and then sees 10.
     */
    @Test
    @DisplayName("concurrent modify: the widening check sees the committed value, never a stale one")
    void theWideningCheckUsesTheLockedCommittedValue() throws Exception {
        Instant sixty = Instant.now().plus(60, ChronoUnit.DAYS).truncatedTo(ChronoUnit.SECONDS);
        Instant ten = Instant.now().plus(10, ChronoUnit.DAYS).truncatedTo(ChronoUnit.SECONDS);
        Instant thirty = Instant.now().plus(30, ChronoUnit.DAYS).truncatedTo(ChronoUnit.SECONDS);
        long own = seedGrant(ADMIN, false, sixty, "seeded own grant");

        var definition = new DefaultTransactionDefinition();
        definition.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        TransactionTemplate tx = new TransactionTemplate(txManager, definition);

        CountDownLatch t1Locked = new CountDownLatch(1);
        CountDownLatch releaseT1 = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<?> t1 = pool.submit(() -> tx.executeWithoutResult(status -> {
                service.modifyAccess(id(ADMIN), id(ADMIN), own, ten, false, "narrowing my own access");
                t1Locked.countDown();
                try {
                    releaseT1.await(30, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }));
            assertThat(t1Locked.await(30, TimeUnit.SECONDS)).isTrue();

            Future<Throwable> t2 = pool.submit(() -> {
                try {
                    tx.executeWithoutResult(status -> service.modifyAccess(
                            id(ADMIN), id(ADMIN), own, thirty, false, "changing my own expiry"));
                    return null;
                } catch (Throwable failure) {
                    return failure;
                }
            });

            assertThatThrownBy(() -> t2.get(1500, TimeUnit.MILLISECONDS))
                    .as("T2 must wait on T1's row lock, not read the stale value")
                    .isInstanceOf(TimeoutException.class);

            releaseT1.countDown();
            t1.get(30, TimeUnit.SECONDS);
            Throwable outcome = t2.get(30, TimeUnit.SECONDS);

            assertThat(outcome)
                    .as("against the committed value (10 days) 30 days widens")
                    .isInstanceOf(SelfAdministrationException.class);
            assertThat(jdbc.sql("SELECT expires_at FROM authz.access_grant WHERE grant_id = :id")
                    .param("id", own).query(java.sql.Timestamp.class).single().toInstant())
                    .isEqualTo(ten);
            assertThat(count("access_audit")).as("only T1 audited").isEqualTo(1);
        } finally {
            releaseT1.countDown();
            pool.shutdownNow();
        }
    }

    // ---- helpers -------------------------------------------------------

    /** Perform, assert status and code, and assert the database did not change. */
    private String expectUnchanged(RequestBuilder request, int status, String code) throws Exception {
        Map<String, String> before = snapshot();
        var response = mvc().perform(request)
                .andExpect(status().is(status))
                .andExpect(jsonPath("$.code").value(code))
                .andReturn().getResponse();
        assertThat(snapshot()).as("a refusal must change nothing, audit included").isEqualTo(before);
        return com.jayway.jsonpath.JsonPath.read(response.getContentAsString(), "$.message");
    }

    /** One digest per authz table this PR can touch. */
    private Map<String, String> snapshot() {
        Map<String, String> digest = new LinkedHashMap<>();
        for (String table : TABLES) {
            digest.put(table, jdbc.sql("SELECT count(*) || ':' || coalesce(md5(string_agg(t::text, '|' "
                            + "ORDER BY t::text)), '') FROM authz." + table + " t")
                    .query(String.class).single());
        }
        return digest;
    }

    private long seedGrant(String grantee, boolean descendants, Instant expires, String reason) {
        return jdbc.sql("""
                        INSERT INTO authz.access_grant (institutional_identifier, grant_type,
                            unit_number, include_descendants, granted_by, reason, expires_at)
                        VALUES (:g, 'UNIT', '1234', :d, 'seed', :r, :e)
                        RETURNING grant_id
                        """)
                .param("g", grantee).param("d", descendants).param("r", reason)
                .param("e", expires == null ? null : java.sql.Timestamp.from(expires))
                .query(Long.class).single();
    }

    private long linkOf(String who) {
        return jdbc.sql("""
                SELECT identity_link_id FROM authz.identity_link
                 WHERE institutional_identifier = :w AND status = 'ACTIVE'
                """).param("w", who).query(Long.class).single();
    }

    private int activeGrantsFor(String grantee) {
        return jdbc.sql("""
                SELECT count(*) FROM authz.access_grant
                 WHERE institutional_identifier = :g AND revoked_at IS NULL
                """).param("g", grantee).query(Integer.class).single();
    }

    private int count(String table) {
        return jdbc.sql("SELECT count(*) FROM authz." + table).query(Integer.class).single();
    }

    private static String grantBody(String type, String unit, String awardNumber, String reason) {
        StringBuilder body = new StringBuilder("{\"type\":\"").append(type).append('"');
        if (unit != null) {
            body.append(",\"unitNumber\":\"").append(unit).append('"');
        }
        if (awardNumber != null) {
            body.append(",\"awardNumber\":\"").append(awardNumber).append('"');
        }
        return body.append(",\"reason\":\"").append(reason).append("\"}").toString();
    }

    private static InstitutionalIdentifier id(String value) {
        return new InstitutionalIdentifier(value);
    }

    private static String subjectOf(String who) {
        return "sub-" + who.toLowerCase();
    }

    private MockMvc mvc() {
        CurrentIdentityProvider identities = () -> Optional.ofNullable(caller);
        var controller = new AdminAuthorizationController(service, identities,
                new IdentityResolver(new JdbcIdentityLinkRepository(jdbc)), properties,
                Clock.systemUTC());
        return MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    private static <T> T port(Object target, Class<T> port) {
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
