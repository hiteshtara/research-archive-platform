package edu.bu.archive.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import com.fasterxml.jackson.databind.ObjectMapper;

import edu.bu.archive.application.authorization.AuthorizationPolicy;
import edu.bu.archive.application.authorization.AuthorizationProperties;
import edu.bu.archive.config.ActivityAuditFixture.Module;

/**
 * US-SEC-004.1 / .2 (SEC-008), end to end: real HTTP requests through the
 * real controllers, services, Spring wiring and authorization engine, writing
 * to a real {@code authz.activity_audit} (V089 applied to a throwaway
 * container), with collection ON.
 *
 * <p>Every assertion reads the TABLE, not a mock, so it proves what an
 * auditor would actually find. It drives the application only through HTTP
 * and reads only the database, so the same file runs unchanged against the
 * code before the fix - which is how each defect below was demonstrated:
 *
 * <ul>
 *   <li><b>missing events</b> - record views and refusals were never written
 *       even with collection on (the RecordAuthorizationService bean was
 *       built with a disabled recorder);
 *   <li><b>no actor, no correlation id</b> - the recorder was wired with
 *       {@code () -> Optional.empty()} and {@code () -> null};
 *   <li><b>incomplete coverage</b> - nothing was recorded with enforcement
 *       off, and Subaward / Negotiation decisions were never recorded at all;
 *   <li><b>false release rows</b> - a download refused at the record level
 *       still left an {@code ATTACHMENT_DOWNLOADED / PERMITTED} row.
 * </ul>
 *
 * <p>Synthetic identities and records only. IRB is out of release scope.
 */
@SpringBootTest(properties = {
        "app.authorization.activity-audit.enabled=true",
        "app.authorization.activity-audit.routine-read-failure-policy=FAIL_OPEN",
        "app.authorization.activity-audit.sensitive-release-failure-policy=FAIL_CLOSED"
})
@AutoConfigureMockMvc
@org.springframework.context.annotation.Import(DeferredAsyncExecutorConfiguration.class)
@ActiveProfiles("local")
@Testcontainers
@Tag("database")
class ActivityAuditEndToEndIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg17")
                    .asCompatibleSubstituteFor("postgres"));

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        ActivityAuditFixture.attachmentStorage(registry);
    }

    @BeforeAll
    static void prepareDatabase() throws Exception {
        ActivityAuditFixture.prepare(postgres);
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private DeferredAsyncExecutorConfiguration.DeferredAsyncExecutor asyncExecutor;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private AuthorizationProperties authorization;

    private static final long ATTACHMENT_ID = ActivityAuditFixture.ATTACHMENT_ID;
    private static final ObjectMapper JSON = new ObjectMapper();

    /** One activity row, as an auditor would read it. */
    record Row(Instant occurredAt, String actor, String action, String resourceType,
               String resourceKey, String outcome, String reason, String correlationId,
               String detail) {

        @SuppressWarnings("unchecked")
        Map<String, Object> detailMap() throws Exception {
            return detail == null ? Map.of() : JSON.readValue(detail, Map.class);
        }
    }

    @BeforeEach
    void cleanSlate() {
        // TRUNCATE does not fire the append-only row trigger; DELETE would.
        jdbc.sql("TRUNCATE authz.activity_audit").update();
        enforcement(false);
    }

    @AfterEach
    void enforcementOff() {
        enforcement(false);
    }

    // ── US-SEC-004.1: record views ─────────────────────────────────────────

    @ParameterizedTest(name = "{0}")
    @EnumSource(Module.class)
    @DisplayName("enforcement ON: a permitted view writes exactly one RECORD_VIEWED row with all five fields")
    void permittedViewIsRecordedOnceWithActorTargetTimeOutcomeAndCorrelation(Module module)
            throws Exception {
        enforcement(true);
        Instant before = Instant.now().minusSeconds(1);

        perform(module.view(module.permittedId), ActivityAuditFixture.linkedUser());

        List<Row> views = rows("RECORD_VIEWED");
        assertThat(views)
                .as("one permitted %s view must write exactly one RECORD_VIEWED row", module)
                .hasSize(1);
        Row row = views.get(0);
        assertThat(row.actor())
                .as("actor must be the caller's resolved institutional identifier")
                .isEqualTo(ActivityAuditFixture.INSTITUTIONAL_ID);
        assertThat(row.resourceType()).as("target module").isEqualTo(module.name());
        assertThat(row.resourceKey()).as("target record").isEqualTo(module.permittedKey);
        assertThat(row.outcome()).as("outcome").isEqualTo("PERMITTED");
        assertThat(row.occurredAt()).as("timestamp")
                .isBetween(before, Instant.now().plusSeconds(1));
        assertThat(row.correlationId()).as("correlation id").isNotBlank();
        assertThat(rows("RECORD_ACCESS_DENIED"))
                .as("a permitted view must not also be recorded as a denial")
                .isEmpty();
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(Module.class)
    @DisplayName("enforcement ON: a denied view writes exactly one RECORD_ACCESS_DENIED row")
    void deniedViewIsRecordedAsAccessDenied(Module module) throws Exception {
        enforcement(true);

        perform(module.view(module.deniedId), ActivityAuditFixture.linkedUser());

        List<Row> denied = rows("RECORD_ACCESS_DENIED");
        assertThat(denied)
                .as("one refused %s view must write exactly one RECORD_ACCESS_DENIED row", module)
                .hasSize(1);
        Row row = denied.get(0);
        assertThat(row.actor()).isEqualTo(ActivityAuditFixture.INSTITUTIONAL_ID);
        assertThat(row.resourceType()).isEqualTo(module.name());
        assertThat(row.resourceKey()).isEqualTo(module.deniedKey);
        assertThat(row.outcome()).isEqualTo("DENIED");
        assertThat(row.reason()).as("a denial carries the engine's code").isNotBlank();
        assertThat(row.correlationId()).isNotBlank();
        assertThat(rows("RECORD_VIEWED")).as("a refused record was not viewed").isEmpty();
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(Module.class)
    @DisplayName("enforcement OFF, audit ON: a view is still recorded, PERMITTED / NOT_ENFORCED")
    void viewIsRecordedWhenEnforcementIsOff(Module module) throws Exception {
        perform(module.view(module.permittedId), ActivityAuditFixture.linkedUser());

        List<Row> views = rows("RECORD_VIEWED");
        assertThat(views)
                .as("audit must not depend on enforcement: a %s view with enforcement off "
                        + "must still be recorded", module)
                .hasSize(1);
        Row row = views.get(0);
        assertThat(row.actor()).isEqualTo(ActivityAuditFixture.INSTITUTIONAL_ID);
        assertThat(row.resourceType()).isEqualTo(module.name());
        assertThat(row.resourceKey()).isEqualTo(module.permittedKey);
        assertThat(row.outcome()).isEqualTo("PERMITTED");
        assertThat(row.reason()).isEqualTo("NOT_ENFORCED");
        assertThat(row.correlationId()).isNotBlank();
    }

    @Test
    @DisplayName("each request gets its own correlation id")
    void separateRequestsHaveSeparateCorrelationIds() throws Exception {
        perform(Module.AWARD.view(Module.AWARD.permittedId), ActivityAuditFixture.linkedUser());
        perform(Module.PROPOSAL.view(Module.PROPOSAL.permittedId), ActivityAuditFixture.linkedUser());

        List<Row> views = rows("RECORD_VIEWED");
        assertThat(views).hasSize(2);
        assertThat(views).extracting(Row::correlationId).doesNotContainNull().doesNotHaveDuplicates();
    }

    @Test
    @DisplayName("an authenticated caller with no identity link is attributed by Cognito subject, not null")
    void anUnlinkedCallerIsStillAttributed() throws Exception {
        perform(Module.AWARD.view(Module.AWARD.permittedId), ActivityAuditFixture.unlinkedUser());

        assertThat(rows("RECORD_VIEWED")).singleElement()
                .extracting(Row::actor)
                .isEqualTo("cognito-sub:" + ActivityAuditFixture.UNLINKED_SUBJECT);
    }

    @Test
    @DisplayName("a Subaward decided through its funding Award records the Subaward, not the Award")
    void inheritedAwardChecksAreNotRecordedAsAwardViews() throws Exception {
        enforcement(true);

        perform(Module.SUBAWARD.view(Module.SUBAWARD.permittedId), ActivityAuditFixture.linkedUser());

        assertThat(rows(null))
                .as("only the Subaward the caller opened, never the Award checked on its behalf")
                .extracting(Row::resourceType)
                .containsOnly("SUBAWARD");
    }

    /**
     * B1 (#159) scopes related-record lists in SQL through relatedScopeSql.
     * Listing related records is not opening them: only the PARENT Award's
     * view may be recorded, never one row per related Proposal, Subaward or
     * Negotiation - with enforcement on or off.
     */
    @ParameterizedTest(name = "enforcement={0}")
    @org.junit.jupiter.params.provider.ValueSource(booleans = {true, false})
    @DisplayName("a related-record list records only the parent's RECORD_VIEWED, not the related records")
    void relatedRecordListsRecordOnlyTheParentView(boolean enforced) throws Exception {
        enforcement(enforced);
        Map<String, String> listedMarker = Map.of(
                "/api/v1/awards/5002/funding-proposals", "8001",
                "/api/v1/awards/5002/funding-subawards", "SYN-SUB-OK",
                "/api/v1/awards/5002/negotiations", "SYN-NDOC-102");

        for (Map.Entry<String, String> endpoint : listedMarker.entrySet()) {
            jdbc.sql("TRUNCATE authz.activity_audit").update();
            var response = exchange(get(endpoint.getKey())
                    .with(ActivityAuditFixture.linkedUser()));
            assertThat(response.getStatus()).as(endpoint.getKey()).isEqualTo(200);
            assertThat(response.getContentAsString())
                    .as("%s really listed a related record", endpoint.getKey())
                    .contains(endpoint.getValue());

            assertThat(rows(null))
                    .as("%s: exactly one row - the parent Award's view", endpoint.getKey())
                    .singleElement()
                    .satisfies(row -> {
                        assertThat(row.action()).isEqualTo("RECORD_VIEWED");
                        assertThat(row.resourceType()).isEqualTo("AWARD");
                        assertThat(row.resourceKey()).isEqualTo(Module.AWARD.permittedKey);
                        assertThat(row.actor()).isEqualTo(ActivityAuditFixture.INSTITUTIONAL_ID);
                    });
        }
    }

    /**
     * Production thread-safety evidence. Concurrent requests share the
     * recorder, auditor, JDBC port and RequestActivityContext; only the
     * per-request caches (actor, correlation id, de-duplication set) live on
     * each request. If any of that state leaked across threads, rows would be
     * lost, duplicated, mis-attributed or share a correlation id.
     */
    @Test
    @DisplayName("concurrent requests each record exactly their own row - none lost, none shared")
    void concurrentRequestsRecordTheirOwnRowsWithNoneLost() throws Exception {
        enforcement(true);
        int threads = 8;
        int perThread = 25;
        var pool = java.util.concurrent.Executors.newFixedThreadPool(threads);
        try {
            var start = new java.util.concurrent.CountDownLatch(1);
            List<java.util.concurrent.Future<Integer>> statuses = new java.util.ArrayList<>();
            for (int t = 0; t < threads; t++) {
                Module module = Module.values()[t % Module.values().length];
                statuses.add(pool.submit(() -> {
                    start.await();
                    int ok = 0;
                    for (int i = 0; i < perThread; i++) {
                        if (exchange(get(module.view(module.permittedId))
                                .with(ActivityAuditFixture.linkedUser())).getStatus() == 200) {
                            ok++;
                        }
                    }
                    return ok;
                }));
            }
            start.countDown();
            int served = 0;
            for (var status : statuses) {
                served += status.get(120, java.util.concurrent.TimeUnit.SECONDS);
            }
            assertThat(served).isEqualTo(threads * perThread);
        } finally {
            pool.shutdownNow();
        }

        List<Row> views = rows("RECORD_VIEWED");
        assertThat(views).as("one row per request, none lost or duplicated")
                .hasSize(threads * perThread);
        assertThat(views).extracting(Row::correlationId)
                .as("every request has its own correlation id")
                .doesNotContainNull()
                .doesNotHaveDuplicates();
        assertThat(views).extracting(Row::actor)
                .containsOnly(ActivityAuditFixture.INSTITUTIONAL_ID);
        for (Module module : Module.values()) {
            long expected = (long) perThread * java.util.stream.IntStream.range(0, threads)
                    .filter(t -> Module.values()[t % Module.values().length] == module).count();
            assertThat(views).filteredOn(row -> row.resourceType().equals(module.name()))
                    .as("%s rows", module)
                    .hasSize((int) expected)
                    .extracting(Row::resourceKey).containsOnly(module.permittedKey);
        }
    }

    // ── US-SEC-004.2: document downloads ───────────────────────────────────

    @ParameterizedTest(name = "{0}")
    @EnumSource(Module.class)
    @DisplayName("a download writes ATTACHMENT_DOWNLOADED with actor, target, attachment id, time and outcome")
    void downloadIsRecordedWithAllFiveFields(Module module) throws Exception {
        enforcement(true);
        Instant before = Instant.now().minusSeconds(1);

        var response = exchange(get(module.download(module.permittedId, ATTACHMENT_ID))
                .with(ActivityAuditFixture.linkedUser()));
        assertThat(response.getStatus()).as("the file was really released").isEqualTo(200);
        assertThat(response.getContentAsString())
                .as("the stored file's bytes, streamed in full")
                .isEqualTo(ActivityAuditFixture.SECRET_CONTENT);

        List<Row> downloads = rows("ATTACHMENT_DOWNLOADED");
        assertThat(downloads)
                .as("one %s download must write exactly one ATTACHMENT_DOWNLOADED row", module)
                .hasSize(1);
        Row row = downloads.get(0);
        assertThat(row.actor()).as("actor").isEqualTo(ActivityAuditFixture.INSTITUTIONAL_ID);
        assertThat(row.resourceType()).as("target module").isEqualTo(module.name());
        assertThat(row.resourceKey())
                .as("target record: the same business key a view of it carries")
                .isEqualTo(module.permittedKey);
        assertThat(row.detailMap()).as("target record id")
                .containsEntry("recordId", (int) module.permittedId);
        assertThat(row.detailMap()).as("target attachment id")
                .containsEntry("attachmentId", (int) ATTACHMENT_ID);
        assertThat(row.occurredAt()).as("timestamp")
                .isBetween(before, Instant.now().plusSeconds(1));
        assertThat(row.outcome()).as("outcome").isEqualTo("PERMITTED");
        assertThat(row.correlationId()).as("correlation id").isNotBlank();
        assertThat(rows("RECORD_VIEWED")).singleElement()
                .extracting(Row::resourceKey, Row::correlationId)
                .as("the view and the download of one request share key and correlation id")
                .containsExactly(module.permittedKey, row.correlationId());

        assertThat(row.detailMap().keySet())
                .as("a download row holds ids and the release phase - no file name, no content")
                .isSubsetOf(Set.of("phase", "attachmentId", "recordId"));
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(Module.class)
    @DisplayName("another record's attachment id, requested under a permitted record, writes no download row")
    void aForeignAttachmentIdWritesNoDownloadRow(Module module) throws Exception {
        enforcement(true);

        var response = exchange(get(module.download(module.permittedId,
                        ActivityAuditFixture.FOREIGN_ATTACHMENT_ID))
                .with(ActivityAuditFixture.linkedUser()));

        assertThat(response.getStatus()).isEqualTo(404);
        assertThat(rows("ATTACHMENT_DOWNLOADED"))
                .as("%s: the attachment does not belong to the record, nothing was released", module)
                .isEmpty();
        assertThat(rows(null)).noneMatch(row ->
                row.action().equals("ATTACHMENT_DOWNLOADED") && row.outcome().equals("PERMITTED"));
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(Module.class)
    @DisplayName("an attachment id that does not exist writes no download row")
    void aMissingAttachmentIdWritesNoDownloadRow(Module module) throws Exception {
        enforcement(true);

        var response = exchange(get(module.download(module.permittedId,
                        ActivityAuditFixture.MISSING_ATTACHMENT_ID))
                .with(ActivityAuditFixture.linkedUser()));

        assertThat(response.getStatus()).isEqualTo(404);
        assertThat(rows("ATTACHMENT_DOWNLOADED")).as("%s: nothing was released", module).isEmpty();
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(Module.class)
    @DisplayName("a download refused at the record level writes no ATTACHMENT_DOWNLOADED row")
    void aRefusedDownloadIsNotRecordedAsARelease(Module module) throws Exception {
        enforcement(true);

        perform(module.download(module.deniedId, ATTACHMENT_ID), ActivityAuditFixture.linkedUser());

        assertThat(rows("ATTACHMENT_DOWNLOADED"))
                .as("nothing was released, so there must be no PERMITTED release row")
                .isEmpty();
        assertThat(rows("RECORD_ACCESS_DENIED")).hasSize(1);
    }

    // ── exports ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("an Award report export writes EXPORT_GENERATED with the actor")
    void exportIsRecordedWithActor() throws Exception {
        enforcement(true);

        perform("/api/v1/awards/%d/report.pdf".formatted(Module.AWARD.permittedId),
                ActivityAuditFixture.linkedUser());

        List<Row> exports = rows("EXPORT_GENERATED");
        assertThat(exports).hasSize(1);
        Row row = exports.get(0);
        assertThat(row.actor()).isEqualTo(ActivityAuditFixture.INSTITUTIONAL_ID);
        assertThat(row.resourceType()).isEqualTo("AWARD");
        assertThat(row.resourceKey()).isEqualTo(Module.AWARD.permittedKey);
        assertThat(row.outcome()).isEqualTo("PERMITTED");
        assertThat(row.correlationId()).isNotBlank();
        assertThat(row.detailMap().keySet()).isSubsetOf(Set.of("phase", "recordId"));
    }

    // ── failure policy ─────────────────────────────────────────────────────

    @Test
    @DisplayName("audit store failing: downloads and exports are refused (FAIL_CLOSED), no file is released")
    void aFailedAuditWriteRefusesDownloadsAndExports() throws Exception {
        enforcement(true);
        // Break every activity INSERT the way a real outage would: the write
        // throws. Owner-only DDL in the throwaway container, removed after.
        jdbc.sql("""
                CREATE OR REPLACE FUNCTION authz.test_break_activity_audit() RETURNS trigger
                LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'simulated audit store outage'; END $$
                """).update();
        jdbc.sql("""
                CREATE TRIGGER tr_test_break_activity_audit BEFORE INSERT ON authz.activity_audit
                FOR EACH ROW EXECUTE FUNCTION authz.test_break_activity_audit()
                """).update();
        try {
            long awardId = Module.AWARD.permittedId;
            for (String release : List.of(
                    Module.AWARD.download(awardId, ATTACHMENT_ID),
                    "/api/v1/awards/%d/report.pdf".formatted(awardId))) {
                var response = exchange(get(release).with(ActivityAuditFixture.linkedUser()));
                assertThat(response.getStatus()).as(release).isEqualTo(503);
                assertThat(response.getContentAsString()).contains("AUDIT_UNAVAILABLE")
                        .doesNotContain("simulated audit store outage")
                        .as("not a byte of the file reached the caller")
                        .doesNotContain(ActivityAuditFixture.SECRET_CONTENT);
                assertThat(response.getHeader("Content-Disposition"))
                        .as("%s: no file was released", release).isNull();
                assertThat(response.getContentType()).doesNotContain("pdf");
            }

            // A routine view under the FAIL_OPEN setting this context
            // configures explicitly is still served.
            assertThat(exchange(get(Module.AWARD.view(awardId))
                    .with(ActivityAuditFixture.linkedUser())).getStatus())
                    .isEqualTo(200);
        } finally {
            jdbc.sql("DROP TRIGGER tr_test_break_activity_audit ON authz.activity_audit").update();
            jdbc.sql("DROP FUNCTION authz.test_break_activity_audit()").update();
        }
    }

    // ── sensitive data (req 10) ────────────────────────────────────────────

    @Test
    @DisplayName("no row stores a token, an Authorization header, a file name, document text or a search term")
    void rowsNeverContainTokensFileNamesContentOrSearchTerms() throws Exception {
        enforcement(true);
        String authorization = "Bearer " + ActivityAuditFixture.SECRET_TOKEN;
        long awardId = Module.AWARD.permittedId;

        for (String path : List.of(
                Module.AWARD.view(awardId),
                Module.AWARD.download(awardId, ATTACHMENT_ID),
                "/api/v1/awards/%d/report.pdf".formatted(awardId),
                "/api/v1/awards/%d/attachments".formatted(awardId),
                "/api/global-search?query=" + ActivityAuditFixture.SECRET_SEARCH_TERM)) {
            exchange(get(path)
                    .header("Authorization", authorization)
                    .with(ActivityAuditFixture.linkedUser()));
        }

        List<Row> written = rows(null);
        assertThat(written).extracting(Row::action)
                .as("the view, the download, the export and the search were all recorded")
                .contains("RECORD_VIEWED", "ATTACHMENT_DOWNLOADED", "EXPORT_GENERATED",
                        "SEARCH_PERFORMED");

        String everything = jdbc.sql("""
                        SELECT string_agg(concat_ws('|', actor, action, resource_type, resource_key,
                                                   outcome, reason, correlation_id, detail::text), E'\\n')
                          FROM authz.activity_audit
                        """).query(String.class).single();
        assertThat(everything)
                .as("credentials, tokens, file names, document text and search terms never reach a row")
                .doesNotContain(ActivityAuditFixture.SECRET_TOKEN)
                .doesNotContain(authorization)
                .doesNotContainIgnoringCase("bearer")
                .doesNotContain(ActivityAuditFixture.SECRET_FILE_NAME)
                .doesNotContain(ActivityAuditFixture.SECRET_DESCRIPTION)
                .doesNotContainIgnoringCase(ActivityAuditFixture.SECRET_SEARCH_TERM)
                .doesNotContain("%PDF");
        assertThat(written).filteredOn(row -> row.action().equals("SEARCH_PERFORMED"))
                .allSatisfy(row -> {
                    assertThat(row.resourceKey()).as("a search has no key").isNull();
                    assertThat(row.detailMap().keySet()).containsOnly("resultCount");
                });
    }

    // ── helpers ────────────────────────────────────────────────────────────

    private void perform(String path, RequestPostProcessor user) throws Exception {
        // The status is not asserted: an attachment with no stored file 404s
        // AFTER the audit decision, which is the point under test.
        exchange(get(path).with(user));
    }

    private List<Row> rows(String action) {
        return jdbc.sql("""
                        SELECT occurred_at, actor, action, resource_type, resource_key,
                               outcome, reason, correlation_id, detail::text AS detail
                          FROM authz.activity_audit
                         WHERE (CAST(:action AS TEXT) IS NULL OR action = :action)
                         ORDER BY activity_id
                        """)
                .param("action", action)
                .query((rs, i) -> new Row(
                        rs.getTimestamp("occurred_at").toInstant(),
                        rs.getString("actor"),
                        rs.getString("action"),
                        rs.getString("resource_type"),
                        rs.getString("resource_key"),
                        rs.getString("outcome"),
                        rs.getString("reason"),
                        rs.getString("correlation_id"),
                        rs.getString("detail")))
                .list();
    }

    private void enforcement(boolean on) {
        authorization.setEnforcementEnabled(on);
        if (on) {
            authorization.setVersionScope(AuthorizationPolicy.VersionScope.FAMILY_WIDE);
            authorization.setDepartmentMatch(AuthorizationPolicy.DepartmentMatch.EXACT_LEAD_UNIT);
            authorization.setResearchStaffRoles(Set.of("PI"));
            authorization.setContactDerivation(AuthorizationPolicy.ContactDerivation.EXPLICIT_GRANT);
            authorization.setMaxSignInAge(Duration.ofHours(8));
        }
    }

    /**
     * One request, run strictly sequentially: the dispatch, then (for a
     * StreamingResponseBody download or report) the queued body on this
     * thread, then the async dispatch. See DeferredAsyncExecutorConfiguration.
     */
    private org.springframework.mock.web.MockHttpServletResponse exchange(
            org.springframework.test.web.servlet.RequestBuilder request) throws Exception {
        org.springframework.test.web.servlet.MvcResult result = mockMvc.perform(request).andReturn();
        if (result.getRequest().isAsyncStarted()) {
            asyncExecutor.runPending();
            return mockMvc.perform(org.springframework.test.web.servlet.request
                    .MockMvcRequestBuilders.asyncDispatch(result)).andReturn().getResponse();
        }
        return result.getResponse();
    }
}
