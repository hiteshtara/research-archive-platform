package edu.bu.archive.authorization;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import edu.bu.archive.application.authorization.CurrentIdentityProvider;
import edu.bu.archive.application.authorization.IoResolver;
import edu.bu.archive.application.authorization.IoSqlStrategy;
import edu.bu.archive.application.authorization.RecordModule;
import edu.bu.archive.application.authorization.ValidatedCognitoIdentity;

/**
 * Record authorization ENFORCED, end to end: the real application, real
 * identity links and grants, real scoped SQL and request guards, against a
 * disposable Postgres loaded with every migration and the SYNTHETIC seed
 * (api/src/test/resources/authz/synthetic-seed.sql, also used by the local demo). Only sign-in is replaced, by a
 * TEST-ONLY identity provider reading the X-Test-Persona header.
 *
 * <p>Fixture-based authorization verified; real BU federation and identity
 * mapping NOT VERIFIED. Policy choices configured here are the DEMO
 * configuration of unapproved proposals (P3 PER_VERSION, P6 EXACT_LEAD_UNIT,
 * P4 PI/MPI/COI), not approved policy.
 */
@Testcontainers
@SpringBootTest(properties = {
        "app.security.enabled=false",
        "app.authorization.enforcement-enabled=true",
        "app.authorization.version-scope=PER_VERSION",
        "app.authorization.department-match=EXACT_LEAD_UNIT",
        "app.authorization.research-staff-roles=PI,MPI,COI",
        "app.attachments.storage=local",
        "app.attachments.local-directory=target/authz-test-attachments",
        "app.ai.enabled=true",
        "app.ai.stub-enabled=true",
        "app.ai.provider=stub",
        "app.explorer.enabled=true",
        "app.search.semantic.enabled=false"
})
@AutoConfigureMockMvc
class RecordAuthorizationEnforcementIntegrationTest {

    static final String ISSUER = "https://demo.invalid/synthetic-cognito";
    private static final Pattern MIGRATION = Pattern.compile("^V(\\d+)__.*\\.sql$");

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg17").asCompatibleSubstituteFor("postgres"));

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @BeforeAll
    static void loadSchemaAndSyntheticSeed() throws Exception {
        Path root = Path.of("").toAbsolutePath();
        while (!Files.isDirectory(root.resolve("database/migrations"))) {
            root = root.getParent();
        }
        List<Path> migrations;
        try (Stream<Path> files = Files.list(root.resolve("database/migrations"))) {
            migrations = files.filter(p -> MIGRATION.matcher(p.getFileName().toString()).matches())
                    .sorted(Comparator.comparingInt(p -> Integer.parseInt(
                            MIGRATION.matcher(p.getFileName().toString()).replaceAll("$1"))))
                    .toList();
        }
        try (Connection c = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
             Statement s = c.createStatement()) {
            for (Path migration : migrations) {
                s.execute(Files.readString(migration));
            }
            s.execute(Files.readString(root.resolve("api/src/test/resources/authz/synthetic-seed.sql")));
        }
    }

    /** TEST-ONLY sign-in replacement and synthetic IO mapping (mirrors the demo build). */
    @TestConfiguration
    static class SyntheticIdentity {
        @Bean
        @Primary
        CurrentIdentityProvider testIdentity() {
            return () -> Optional.ofNullable(RequestContextHolder.getRequestAttributes())
                    .map(a -> ((ServletRequestAttributes) a).getRequest().getHeader("X-Test-Persona"))
                    .map(key -> new ValidatedCognitoIdentity(ISSUER, "demo-" + key));
        }

        @Bean
        @Primary
        IoResolver testIoResolver(JdbcClient jdbc) {
            return (module, key) -> module != RecordModule.AWARD ? Set.of()
                    : Set.copyOf(jdbc.sql("SELECT io_value FROM authz_demo.award_io WHERE award_id = CAST(:id AS BIGINT)")
                            .param("id", key).query(String.class).list());
        }

        @Bean
        @Primary
        IoSqlStrategy testIoSql() {
            return (module, alias) -> module == RecordModule.AWARD
                    ? Optional.of("EXISTS (SELECT 1 FROM authz_demo.award_io az_io WHERE az_io.award_id = "
                            + alias + ".award_id AND az_io.io_value IN (:az_ios))")
                    : Optional.empty();
        }
    }

    @Autowired MockMvc mvc;
    @Autowired JdbcClient jdbc;
    private final ObjectMapper json = new ObjectMapper();

    private MvcResult call(String persona, String path, boolean attachmentGroup) throws Exception {
        MockHttpServletRequestBuilder request = get(path).header("X-Test-Persona", persona);
        if (attachmentGroup) {
            request = request.with(user("synthetic").roles("ArchiveAttachmentViewer"));
        }
        return mvc.perform(request).andReturn();
    }

    private int status(String persona, String path) throws Exception {
        return call(persona, path, false).getResponse().getStatus();
    }

    private JsonNode body(String persona, String path) throws Exception {
        MvcResult result = call(persona, path, false);
        assertThat(result.getResponse().getStatus()).as(persona + " " + path).isEqualTo(200);
        return json.readTree(result.getResponse().getContentAsString());
    }

    private Set<String> awardFamilies(String persona) throws Exception {
        JsonNode page = body(persona, "/api/v1/awards/search?size=100").get("results");
        Set<String> numbers = new TreeSet<>();
        page.get("content").forEach(n -> numbers.add(n.get("awardNumber").asText()));
        assertThat(page.get("totalElements").asLong()).as("count = page for " + persona).isEqualTo(numbers.size());
        return numbers;
    }

    private Set<Long> awardVersions(String persona) throws Exception {
        JsonNode page = body(persona, "/api/v1/awards/versions/search?size=100");
        Set<Long> ids = new TreeSet<>();
        page.get("content").forEach(n -> ids.add(n.get("awardId").asLong()));
        assertThat(page.get("totalElements").asLong()).as("count = page for " + persona).isEqualTo(ids.size());
        return ids;
    }

    @Test
    void searchesAndCountsAreScopedPerPersona() throws Exception {
        assertThat(awardFamilies("central")).hasSize(9);
        assertThat(awardFamilies("department")).containsExactly("990001-00001", "990001-00002");
        assertThat(awardFamilies("pi")).containsExactly("990001-00001", "990003-00001", "990004-00001");
        assertThat(awardFamilies("oav")).containsExactly("990006-00001");
        assertThat(awardFamilies("multi"))
                .containsExactly("990003-00001", "990004-00001", "990005-00001", "990008-00001");

        assertThat(awardVersions("pi")).containsExactly(9000101L, 9000102L, 9000301L, 9000401L);
        assertThat(awardVersions("department")).containsExactly(9000101L, 9000102L, 9000111L);
    }

    @Test
    void researchStaffRolesFollowTheConfiguredPolicy() throws Exception {
        assertThat(status("pi", "/api/v1/awards/9000102/summary")).isEqualTo(200); // PI
        assertThat(status("pi", "/api/v1/awards/9000301/summary")).isEqualTo(200); // MPI (Co-PI)
        assertThat(status("pi", "/api/v1/awards/9000401/summary")).isEqualTo(200); // COI
        assertThat(status("pi", "/api/v1/awards/9000501/summary")).isEqualTo(404); // KP excluded (P4)
    }

    @Test
    void outOfScopeRecordsAreDeniedByDirectUrlWithoutConfirmingTheyExist() throws Exception {
        for (String path : List.of("/api/v1/awards/9000201/summary", "/api/v1/awards/9000201/people",
                "/api/v1/awards/9000201/report.pdf", "/api/v1/awards/9000201/attachments",
                "/api/v1/awards/9000201/attachments/9300002/download", "/api/v1/awards/by-number/990002-00001",
                "/api/v1/awards/990002-00001/hierarchy", "/api/v1/awards/9000111/summary")) {
            assertThat(status("pi", path)).as(path).isEqualTo(404);
        }
        assertThat(status("pi", "/api/v1/awards/99999999/summary")).isEqualTo(404); // nonexistent: same answer
        assertThat(status("department", "/api/v1/awards/9000701/summary")).isEqualTo(404); // sub-unit, exact match (P6)
        assertThat(status("oav", "/api/v1/awards/9000601/summary")).isEqualTo(200);
        assertThat(status("oav", "/api/v1/awards/9000801/summary")).isEqualTo(404);
        assertThat(status("multi", "/api/v1/awards/9000801/summary")).isEqualTo(200);
        assertThat(status("multi", "/api/v1/awards/9000601/summary")).isEqualTo(404);
    }

    @Test
    void attachmentsStillRequireTheAttachmentGroupInAdditionToRecordAccess() throws Exception {
        assertThat(call("pi", "/api/v1/awards/9000102/attachments", false).getResponse().getStatus()).isEqualTo(403);
        assertThat(call("pi", "/api/v1/awards/9000102/attachments", true).getResponse().getStatus()).isEqualTo(200);
        assertThat(call("pi", "/api/v1/awards/9000201/attachments", true).getResponse().getStatus()).isEqualTo(404);
    }

    @Test
    void relatedRecordsAreListedOnlyWhenThemselvesAccessible() throws Exception {
        assertThat(body("pi", "/api/v1/awards/9000102/funding-proposals")).isEmpty();
        assertThat(body("central", "/api/v1/awards/9000102/funding-proposals")).hasSize(1);
        assertThat(body("pi", "/api/v1/awards/9000102/negotiations")).isEmpty();
        assertThat(body("central", "/api/v1/awards/9000102/negotiations")).hasSize(1);
        assertThat(body("pi", "/api/v1/awards/9000102/funding-subawards")).isEmpty();
        assertThat(body("central", "/api/v1/awards/9000102/funding-subawards")).hasSize(1);
        assertThat(body("pi", "/api/v1/awards/990001-00001/hierarchy").get("root").get("children")).isEmpty();
        assertThat(body("central", "/api/v1/awards/990001-00001/hierarchy").get("root").get("children")).hasSize(1);
        assertThat(body("department", "/api/v1/awards/990001-00001/hierarchy").get("root").get("children")).hasSize(1);
        assertThat(body("pi", "/api/v1/awards/9000102/versions").get("totalElements").asLong()).isEqualTo(2);
    }

    @Test
    void reportsAreAvailableForAnInScopeAwardBecauseEverySectionIsScoped() throws Exception {
        MvcResult report = call("pi", "/api/v1/awards/9000102/report.pdf", false);
        assertThat(report.getResponse().getStatus()).isEqualTo(200);
        assertThat(report.getResponse().getContentType()).isEqualTo("application/pdf");
        assertThat(status("pi", "/api/v1/awards/9000201/report.pdf")).isEqualTo(404);
        assertThat(status("pi", "/api/v1/awards/9000103/report.pdf")).isEqualTo(404);

        // The with-attachments variant still needs ArchiveAttachmentViewer, checked before anything is built.
        MvcResult noGroup = call("pi", "/api/v1/awards/9000102/report-with-attachments.pdf", false);
        assertThat(noGroup.getResponse().getStatus()).isEqualTo(403);
        assertThat(noGroup.getResponse().getContentAsString()).contains("ATTACHMENT_ACCESS_DENIED");
        MvcResult withGroup = call("pi", "/api/v1/awards/9000102/report-with-attachments.pdf", true);
        assertThat(withGroup.getResponse().getStatus()).isEqualTo(200);
        assertThat(call("pi", "/api/v1/awards/9000201/report-with-attachments.pdf", true)
                .getResponse().getStatus()).isEqualTo(404);
    }

    // --- Award section endpoints (fixes 1-9) -----------------------------------------------

    @Test
    void timeAndMoneyTransactionsAndDocumentsAreBoundToTheRequestedAwardsFamily() throws Exception {
        String a = "/api/v1/awards/9000102/time-and-money/";
        // Cross-award read: Award B's transaction and document through Award A's URL.
        assertThat(status("pi", a + "transactions/9710003")).isEqualTo(404);
        assertThat(status("pi", a + "documents/SYN-TNM-B1")).isEqualTo(404);
        assertThat(status("department", a + "transactions/9710003")).isEqualTo(404);
        // Same family, both ends visible.
        assertThat(body("pi", a + "transactions/9710001").get("sourceAwardNumber").asText()).isEqualTo("990001-00001");
        assertThat(body("pi", a + "documents/SYN-TNM-A1").get("rootAwardNumber").asText()).isEqualTo("990001-00001");
        // Same family, but the destination (A's child) is not visible to Pat; it is to the department.
        assertThat(status("pi", a + "transactions/9710002")).isEqualTo(404);
        assertThat(status("department", a + "transactions/9710002")).isEqualTo(200);
        // D sits under B: B's document belongs to D's family, but B's number is not shown, and a
        // transaction that only moves B's money is still refused.
        JsonNode bDocViaD = body("pi", "/api/v1/awards/9000401/time-and-money/documents/SYN-TNM-B1");
        assertThat(bDocViaD.get("rootAwardNumber").isNull()).isTrue();
        assertThat(status("pi", "/api/v1/awards/9000401/time-and-money/transactions/9710003")).isEqualTo(404);
        // Central: unchanged behaviour.
        assertThat(status("central", a + "transactions/9710003")).isEqualTo(200);
        assertThat(body("central", a + "documents/SYN-TNM-B1").get("rootAwardNumber").asText()).isEqualTo("990002-00001");
    }

    @Test
    void amountAndTimeAndMoneyHistoryListOnlyVisibleVersionsAndPageAfterFiltering() throws Exception {
        for (String section : List.of("amounts", "time-and-money/history")) {
            JsonNode pi = body("pi", "/api/v1/awards/9000102/" + section + "?size=1");
            assertThat(pi.get("totalElements").asLong()).as(section).isEqualTo(2);
            assertThat(pi.get("totalPages").asLong()).as(section).isEqualTo(2);
            Set<Long> ids = new TreeSet<>();
            for (int page = 0; page < 2; page++) {
                body("pi", "/api/v1/awards/9000102/" + section + "?size=1&page=" + page).get("content")
                        .forEach(n -> ids.add(n.get("awardId").asLong()));
            }
            assertThat(ids).as(section).containsExactly(9000101L, 9000102L);
            assertThat(body("central", "/api/v1/awards/9000102/" + section).get("totalElements").asLong())
                    .as(section).isEqualTo(3);
        }
    }

    @Test
    void familyWideTimeAndMoneyDataNeedsEveryVersionOfTheFamily() throws Exception {
        JsonNode pi = body("pi", "/api/v1/awards/9000102/time-and-money/summary");
        assertThat(pi.get("obligatedTotalAmount").decimalValue()).isEqualByComparingTo("200.00");
        assertThat(pi.get("familyTransactionCount").isNull()).isTrue();
        assertThat(pi.get("lastFamilyTimeAndMoneyDocumentNumber").isNull()).isTrue();
        JsonNode central = body("central", "/api/v1/awards/9000102/time-and-money/summary");
        assertThat(central.get("familyTransactionCount").asLong()).isEqualTo(1);
        assertThat(central.get("lastFamilyTimeAndMoneyDocumentNumber").asText()).isEqualTo("SYN-TNM-A1");

        JsonNode actions = body("pi", "/api/v1/awards/9000102/time-and-money/actions");
        assertThat(actions.get("content")).isEmpty();
        assertThat(actions.get("totalElements").asLong()).isZero();
        assertThat(body("central", "/api/v1/awards/9000102/time-and-money/actions").get("totalElements").asLong()).isEqualTo(1);
        // D is a one-version family Pat can see entirely.
        assertThat(body("pi", "/api/v1/awards/9000401/time-and-money/actions").get("totalElements").asLong()).isEqualTo(1);
    }

    @Test
    void commentsAndNotepadFollowVersionAndFamilyVisibility() throws Exception {
        JsonNode pi = body("pi", "/api/v1/awards/9000102/comments");
        JsonNode piCategory = category(pi, "SYN1");
        assertThat(piCategory.get("history")).hasSize(1);
        assertThat(piCategory.get("history").get(0).get("awardId").asLong()).isEqualTo(9000101L);
        assertThat(pi.get("notepadEntries")).isEmpty();       // A's family is only partly visible
        JsonNode central = body("central", "/api/v1/awards/9000102/comments");
        assertThat(category(central, "SYN1").get("history")).hasSize(2);
        assertThat(central.get("notepadEntries")).hasSize(1);

        JsonNode piD = body("pi", "/api/v1/awards/9000401/comments");
        assertThat(piD.get("notepadEntries")).hasSize(1);
        assertThat(piD.get("notepadEntries").get(0).get("restrictedView").asText()).isEqualTo("N");
        assertThat(body("central", "/api/v1/awards/9000401/comments").get("notepadEntries")).hasSize(2);
    }

    private static JsonNode category(JsonNode comments, String typeCode) {
        for (JsonNode category : comments.get("commentCategories")) {
            if (typeCode.equals(category.get("commentTypeCode").asText())) {
                return category;
            }
        }
        throw new AssertionError("no comment category " + typeCode);
    }

    @Test
    void budgetsOwnedByInvisibleVersionsAreDroppedBeforeSelection() throws Exception {
        assertThat(body("central", "/api/v1/awards/9000102/budget/summary").get("selectedBudgetId").asLong()).isEqualTo(9750003L);
        assertThat(body("pi", "/api/v1/awards/9000102/budget/summary").get("selectedBudgetId").asLong()).isEqualTo(9750001L);
        JsonNode piVersions = body("pi", "/api/v1/awards/9000102/budget/versions");
        assertThat(piVersions.get("totalElements").asLong()).isEqualTo(1);
        assertThat(piVersions.get("content").get(0).get("owningAwardId").asLong()).isEqualTo(9000101L);
        assertThat(body("central", "/api/v1/awards/9000102/budget/versions").get("totalElements").asLong()).isEqualTo(2);
    }

    @Test
    void fundingProposalLinksMadeOnInvisibleVersionsAreOmitted() throws Exception {
        assertThat(status("pi", "/api/v1/proposals/8000201")).isEqualTo(200);   // the Proposal itself is visible
        assertThat(body("pi", "/api/v1/awards/9000301/funding-proposals")).isEmpty();
        assertThat(body("central", "/api/v1/awards/9000301/funding-proposals")).hasSize(1);
    }

    @Test
    void sapTransmissionsOmitInvisibleChildrenAndTheirHierarchyPayloads() throws Exception {
        JsonNode pi = body("pi", "/api/v1/awards/9000102/sap-transmissions").get("content");
        JsonNode piHierarchy = transmission(pi, 9760001L);
        assertThat(piHierarchy.get("children")).hasSize(1);
        assertThat(piHierarchy.get("children").get(0).get("awardNumber").asText()).isEqualTo("990001-00001");
        assertThat(piHierarchy.get("sentData").isNull()).isTrue();
        assertThat(piHierarchy.get("returnedData").isNull()).isTrue();
        assertThat(transmission(pi, 9760002L).get("sentData").asText()).isEqualTo("<syn-sent-a-only/>");

        JsonNode department = transmission(body("department", "/api/v1/awards/9000102/sap-transmissions").get("content"), 9760001L);
        assertThat(department.get("children")).hasSize(2);
        assertThat(department.get("sentData").asText()).isEqualTo("<syn-sent-hierarchy/>");
    }

    private static JsonNode transmission(JsonNode content, long id) {
        for (JsonNode t : content) {
            if (t.get("transmissionId").asLong() == id) {
                return t;
            }
        }
        throw new AssertionError("no transmission " + id);
    }

    @Test
    void summaryShowsRootAndParentNumbersOnlyWhenVisible() throws Exception {
        JsonNode pi = body("pi", "/api/v1/awards/9000401/summary");
        assertThat(pi.get("rootAwardNumber").isNull()).isTrue();
        assertThat(pi.get("parentAwardNumber").isNull()).isTrue();
        JsonNode central = body("central", "/api/v1/awards/9000401/summary");
        assertThat(central.get("rootAwardNumber").asText()).isEqualTo("990002-00001");
        assertThat(central.get("parentAwardNumber").asText()).isEqualTo("990002-00001");
        assertThat(body("department", "/api/v1/awards/9000111/summary").get("parentAwardNumber").asText())
                .isEqualTo("990001-00001");
    }

    @Test
    void unknownAwardSubPathsAreClosedToNonCentralUsers() throws Exception {
        MvcResult unknown = call("pi", "/api/v1/awards/9000102/not-a-section", false);
        assertThat(unknown.getResponse().getStatus()).isEqualTo(403);
        assertThat(unknown.getResponse().getContentAsString()).contains("NOT_AVAILABLE_UNDER_RECORD_AUTHORIZATION");
        assertThat(status("central", "/api/v1/awards/9000102/not-a-section")).isEqualTo(404);
    }

    // --- File Finder, Explorer, AI (fixes 11-13) ------------------------------------------

    @Test
    void archivedFileFinderReturnsOnlyVisibleAwardVersionsForRestrictedUsers() throws Exception {
        String search = "/api/v1/attachments/search?recordNumber=990001-00001";
        assertThat(call("pi", search, false).getResponse().getStatus()).isEqualTo(403);   // attachment group still required
        JsonNode pi = json.readTree(call("pi", search, true).getResponse().getContentAsString());
        assertThat(pi.get("totalElements").asLong()).isEqualTo(1);
        assertThat(pi.get("content").get(0).get("parentId").asLong()).isEqualTo(9000102L);
        JsonNode central = json.readTree(call("central", search, true).getResponse().getContentAsString());
        assertThat(central.get("totalElements").asLong()).isEqualTo(2);

        JsonNode all = json.readTree(call("pi", search + "&recordType=ALL", true).getResponse().getContentAsString());
        assertThat(all.get("totalElements").asLong()).isEqualTo(1);
        assertThat(all.get("content").get(0).get("recordType").asText()).isEqualTo("AWARD");
        JsonNode other = json.readTree(call("pi", "/api/v1/attachments/search?recordNumber=990002-00001", true)
                .getResponse().getContentAsString());
        assertThat(other.get("totalElements").asLong()).isZero();
        JsonNode proposals = json.readTree(call("department", "/api/v1/attachments/search?recordType=PROPOSAL&recordNumber=SYN-PRP-0003", true)
                .getResponse().getContentAsString());
        assertThat(proposals.get("totalElements").asLong()).isZero();
    }

    @Test
    void explorerAwardLookupsAreRecordChecked() throws Exception {
        assertThat(status("pi", "/api/v1/explorer/awards?awardNumber=990001-00001")).isEqualTo(200);
        assertThat(status("pi", "/api/v1/explorer/awards?awardNumber=990002-00001")).isEqualTo(404);
        assertThat(status("pi", "/api/v1/explorer/award-versions?awardId=9000101")).isEqualTo(200);
        assertThat(status("pi", "/api/v1/explorer/award-versions?awardId=9000103")).isEqualTo(404);
        assertThat(status("pi", "/api/v1/explorer/units?unitNumber=SYN-U-100")).isEqualTo(403);
        assertThat(status("central", "/api/v1/explorer/award-versions?awardId=9000103")).isEqualTo(200);
    }

    @Test
    void aiNeedsEveryVersionOfTheAwardFamily() throws Exception {
        MvcResult partial = mvc.perform(post("/api/ai/awards/990001-00001/summary").header("X-Test-Persona", "pi"))
                .andReturn();
        assertThat(partial.getResponse().getStatus()).isEqualTo(403);
        assertThat(partial.getResponse().getContentAsString()).contains("AI_NOT_AVAILABLE_FOR_PARTIAL_ACCESS");
        assertThat(mvc.perform(post("/api/ai/awards/990002-00001/summary").header("X-Test-Persona", "pi"))
                .andReturn().getResponse().getStatus()).isEqualTo(404);
        assertThat(mvc.perform(post("/api/ai/awards/990004-00001/summary").header("X-Test-Persona", "pi"))
                .andReturn().getResponse().getStatus()).isEqualTo(200);
        assertThat(mvc.perform(post("/api/ai/awards/990001-00001/summary").header("X-Test-Persona", "central"))
                .andReturn().getResponse().getStatus()).isEqualTo(200);
    }

    @Test
    void stillClosedPathsStayClosed() throws Exception {
        for (String path : List.of("/api/v1/documents?q=SYNTHETIC", "/api/documents/search?q=SYNTHETIC",
                "/api/awards/990001-00001", "/api/v1/explorer/proposals")) {
            assertThat(status("pi", path)).as(path).isEqualTo(403);
        }
    }

    @Test
    void proposalsAreScopedAndBeingPiOnAnAwardDoesNotGrantItsProposal() throws Exception {
        Set<String> piProposals = new TreeSet<>();
        body("pi", "/api/proposals/search?size=100").get("content").forEach(n -> piProposals.add(n.get("proposalNumber").asText()));
        assertThat(piProposals).containsExactly("SYN-PRP-0002");
        assertThat(status("pi", "/api/v1/proposals/8000201")).isEqualTo(200);
        assertThat(status("pi", "/api/v1/proposals/8000101")).isEqualTo(404);
        assertThat(status("pi", "/api/proposals/SYN-PRP-0001")).isEqualTo(404);
        assertThat(status("department", "/api/proposals/SYN-PRP-0003")).isEqualTo(200);
    }

    @Test
    void dashboardCountsUseTheSameScope() throws Exception {
        JsonNode pi = body("pi", "/api/dashboard");
        assertThat(pi.get("awards").asLong()).isEqualTo(3);
        assertThat(pi.get("awardHistoryRecords").asLong()).isEqualTo(4);
        assertThat(pi.get("proposals").asLong()).isEqualTo(1);
        assertThat(pi.get("negotiations").asLong()).isZero();
        assertThat(body("central", "/api/dashboard").get("awards").asLong()).isEqualTo(9);
    }

    @Test
    void globalSearchIsScopedAndSkipsModulesWithoutNonCentralRules() throws Exception {
        List<String> modules = new ArrayList<>();
        Set<String> identifiers = new TreeSet<>();
        body("pi", "/api/global-search?query=SYNTHETIC").get("results").forEach(n -> {
            modules.add(n.get("module").asText());
            identifiers.add(n.get("identifier").asText());
        });
        assertThat(modules).doesNotContain("NEGOTIATION", "SUBAWARD", "IRB");
        assertThat(identifiers).contains("990001-00001", "SYN-PRP-0002")
                .doesNotContain("990002-00001", "990001-00002", "990005-00001", "SYN-PRP-0001");
        assertThat(body("pi", "/api/global-search?query=9000201").get("results").toString()).doesNotContain("990002-00001");
    }

    @Test
    void pathsNotYetScopedAreClosedToNonCentralUsersOnly() throws Exception {
        MvcResult negotiations = call("pi", "/api/negotiations", false);
        assertThat(negotiations.getResponse().getStatus()).isEqualTo(403);
        assertThat(negotiations.getResponse().getContentAsString()).contains("NOT_AVAILABLE_UNDER_RECORD_AUTHORIZATION");
        assertThat(status("central", "/api/negotiations")).isEqualTo(200);
    }

    @Test
    void unprovisionedDeniedAndUnknownIdentitiesGetNoRecordAccess() throws Exception {
        for (String persona : List.of("nogrants", "unknown")) {
            MvcResult result = call(persona, "/api/v1/awards/search", false);
            assertThat(result.getResponse().getStatus()).as(persona).isEqualTo(403);
            assertThat(result.getResponse().getContentAsString()).contains("ACCESS_NOT_PROVISIONED");
        }
        for (String persona : List.of("revoked", "suspended")) {
            MvcResult result = call(persona, "/api/v1/awards/9000102/summary", false);
            assertThat(result.getResponse().getStatus()).as(persona).isEqualTo(403);
            assertThat(result.getResponse().getContentAsString()).contains("ACCESS_DENIED");
        }
        assertThat(mvc.perform(get("/api/v1/awards/search")).andReturn().getResponse().getStatus()).isEqualTo(403);
        assertThat(body("unknown", "/api/v1/me/access").get("problem").asText()).isEqualTo("ACCESS_NOT_PROVISIONED");
    }

    @Test
    void sqlScopeAndPerRecordChecksAgreeForEveryPersonaAndVersion() throws Exception {
        List<Long> allVersions = jdbc.sql("SELECT award_id FROM archive.award_version ORDER BY award_id")
                .query(Long.class).list();
        for (String persona : List.of("central", "department", "pi", "oav", "multi")) {
            Set<Long> searchable = awardVersions(persona);
            for (Long id : allVersions) {
                boolean direct = status(persona, "/api/v1/awards/" + id + "/summary") == 200;
                assertThat(direct).as(persona + " version " + id).isEqualTo(searchable.contains(id));
            }
        }
    }

    @Test
    void revokingAGrantRemovesExactlyWhatItSuppliedOnTheNextRequest() throws Exception {
        assertThat(status("multi", "/api/v1/awards/9000801/summary")).isEqualTo(200);
        jdbc.sql("UPDATE authz.access_grant SET revoked_at = now(), revoked_by = 'test' "
                + "WHERE institutional_identifier = 'SYN-INST-0006' AND grant_type = 'IO'").update();
        try {
            assertThat(status("multi", "/api/v1/awards/9000801/summary")).isEqualTo(404);
            assertThat(status("multi", "/api/v1/awards/9000301/summary")).isEqualTo(200);
        } finally {
            jdbc.sql("UPDATE authz.access_grant SET revoked_at = NULL, revoked_by = NULL "
                    + "WHERE institutional_identifier = 'SYN-INST-0006' AND grant_type = 'IO'").update();
        }
    }
}
