package edu.bu.archive.authorization;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
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
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import edu.bu.archive.application.authorization.CurrentIdentityProvider;
import edu.bu.archive.application.authorization.ValidatedCognitoIdentity;

/**
 * Record authorization ENFORCED with the policy APPROVED by Hitesh on 2026-10-01 (design section 14):
 * P3 FAMILY_WIDE within one Award number, P6 sub-units only when the grant's include-sub-units flag
 * is set (LEAD_UNIT_WITH_DESCENDANTS), P4 PI/MPI/COI (Key Person excluded), Research Staff via the
 * verified principal. Proves the approved boundaries: family-wide never reaches child Awards or
 * related records, and revoking one basis removes only the access that rested on it.
 * SYNTHETIC seed (api/src/test/resources/authz/synthetic-seed.sql); sign-in replaced (X-Test-Persona).
 */
@Testcontainers
@SpringBootTest(properties = {
        "app.security.enabled=false",
        "app.authorization.enforcement-enabled=true",
        "app.authorization.version-scope=FAMILY_WIDE",
        "app.authorization.department-match=LEAD_UNIT_WITH_DESCENDANTS",
        "app.authorization.research-staff-roles=PI,MPI,COI",
        "app.authorization.contact-derivation=VERIFIED_PRINCIPAL",
        "app.attachments.storage=local",
        "app.attachments.local-directory=target/authz-test-attachments"
})
@AutoConfigureMockMvc
class RecordAuthorizationApprovedPolicyIntegrationTest {

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

    /** TEST-ONLY sign-in replacement. */
    @TestConfiguration
    static class SyntheticIdentity {
        @Bean
        @Primary
        CurrentIdentityProvider testIdentity() {
            return () -> Optional.ofNullable(RequestContextHolder.getRequestAttributes())
                    .map(a -> ((ServletRequestAttributes) a).getRequest().getHeader("X-Test-Persona"))
                    .map(key -> new ValidatedCognitoIdentity(ISSUER, "demo-" + key));
        }
    }

    @Autowired MockMvc mvc;
    @Autowired JdbcClient jdbc;
    private final ObjectMapper json = new ObjectMapper();

    private int status(String persona, String path) throws Exception {
        return mvc.perform(get(path).header("X-Test-Persona", persona)).andReturn().getResponse().getStatus();
    }

    private JsonNode body(String persona, String path) throws Exception {
        var r = mvc.perform(get(path).header("X-Test-Persona", persona)).andReturn().getResponse();
        assertThat(r.getStatus()).as(persona + " " + path).isEqualTo(200);
        return json.readTree(r.getContentAsString());
    }

    private long grant(String institutionalId, String type, String unit, boolean descendants, String io) {
        return jdbc.sql("""
                INSERT INTO authz.access_grant (institutional_identifier, grant_type, unit_number, include_descendants,
                    io_value, granted_by, reason) VALUES (:i, :t, :u, :d, :io, 'it', 'SYNTHETIC') RETURNING grant_id
                """).param("i", institutionalId).param("t", type).param("u", unit).param("d", descendants)
                .param("io", io).query(Long.class).single();
    }

    private void revoke(long grantId) {
        jdbc.sql("UPDATE authz.access_grant SET revoked_by = 'it', revoked_at = now() WHERE grant_id = :g")
                .param("g", grantId).update();
    }

    @Test
    void familyWideOpensEveryVersionOfThePermittedAwardNumber() throws Exception {
        // Pat is PI only on A's versions in SYN-U-100; A' (9000103, SYN-U-200, another PI) opens family-wide.
        assertThat(status("pi", "/api/v1/awards/9000103/summary")).isEqualTo(200);
        // Pat is MPI on C; C' (9000302, another unit, no Pat) opens family-wide.
        assertThat(status("pi", "/api/v1/awards/9000302/summary")).isEqualTo(200);
        assertThat(body("pi", "/api/v1/awards/9000102/versions").get("totalElements").asLong()).isEqualTo(3);
    }

    @Test
    void familyWideNeverReachesChildAwardsRelatedRecordsOrKeyPersonAwards() throws Exception {
        assertThat(status("pi", "/api/v1/awards/9000111/summary")).isEqualTo(404);           // A's child
        assertThat(status("pi", "/api/v1/awards/by-number/990001-00002")).isEqualTo(404);
        assertThat(body("pi", "/api/v1/awards/990001-00001/hierarchy").get("root").get("children")).isEmpty();
        assertThat(status("pi", "/api/proposals/SYN-PRP-0001")).isEqualTo(404);             // A's related Proposal
        assertThat(body("pi", "/api/v1/awards/9000102/funding-proposals")).isEmpty();
        assertThat(body("pi", "/api/v1/awards/9000102/negotiations")).isEmpty();
        assertThat(body("pi", "/api/v1/awards/9000102/funding-subawards")).isEmpty();
        assertThat(status("pi", "/api/v1/awards/9000501/summary")).isEqualTo(404);           // E: Key Person only
        assertThat(status("pi", "/api/v1/awards/9000201/summary")).isEqualTo(404);           // B: D's parent
        var dHierarchy = mvc.perform(get("/api/v1/awards/990004-00001/hierarchy").header("X-Test-Persona", "pi"))
                .andReturn().getResponse();
        assertThat(dHierarchy.getContentAsString()).doesNotContain("SYNTHETIC Award B");
    }

    @Test
    void subUnitsOnlyWhenTheGrantSaysSo() throws Exception {
        // Department's SYN-U-100 grant has include_descendants = TRUE: G (SYN-U-110) opens.
        assertThat(status("department", "/api/v1/awards/9000701/summary")).isEqualTo(200);
        jdbc.sql("UPDATE authz.access_grant SET include_descendants = FALSE "
                + "WHERE institutional_identifier = 'SYN-INST-0002' AND grant_type = 'UNIT'").update();
        try {
            assertThat(status("department", "/api/v1/awards/9000701/summary")).isEqualTo(404);
        } finally {
            jdbc.sql("UPDATE authz.access_grant SET include_descendants = TRUE "
                    + "WHERE institutional_identifier = 'SYN-INST-0002' AND grant_type = 'UNIT'").update();
        }
    }

    @Test
    void revokingOneBasisRemovesOnlyTheAccessThatRestedOnIt() throws Exception {
        // Pat gains a UNIT grant on SYN-U-300: E (unit) opens; C and D are also contact-derived.
        long unit = grant("SYN-INST-0003", "UNIT", "SYN-U-300", false, null);
        assertThat(status("pi", "/api/v1/awards/9000501/summary")).isEqualTo(200);
        revoke(unit);
        assertThat(status("pi", "/api/v1/awards/9000501/summary")).isEqualTo(404);           // no other basis
        assertThat(status("pi", "/api/v1/awards/9000301/summary")).isEqualTo(200);           // contact basis remains
        assertThat(status("pi", "/api/v1/awards/9000401/summary")).isEqualTo(200);

        // Department gains IO SYN-IO-7001 (Award F's account), then loses its UNIT grant.
        long io = grant("SYN-INST-0002", "IO", null, false, "SYN-IO-7001");
        long deptUnit = jdbc.sql("SELECT grant_id FROM authz.access_grant WHERE institutional_identifier = 'SYN-INST-0002' "
                + "AND grant_type = 'UNIT' AND revoked_at IS NULL").query(Long.class).single();
        revoke(deptUnit);
        try {
            assertThat(status("department", "/api/v1/awards/9000102/summary")).isEqualTo(404);   // unit basis gone
            assertThat(status("department", "/api/v1/awards/9000601/summary")).isEqualTo(200);   // IO basis remains
        } finally {
            jdbc.sql("UPDATE authz.access_grant SET revoked_by = NULL, revoked_at = NULL WHERE grant_id = :g")
                    .param("g", deptUnit).update();
            revoke(io);
        }
    }

    @Test
    void aUnitThatLedAnyVersionSeesTheWholeAwardButNotItsChildren() throws Exception {
        // Approved P3 consequence: A' (an old version of A) is in SYN-U-200, so a SYN-U-200 grant opens all of A.
        long g = grant("SYN-INST-0007", "UNIT", "SYN-U-200", false, null);
        try {
            assertThat(status("nogrants", "/api/v1/awards/9000102/summary")).isEqualTo(200);
            assertThat(status("nogrants", "/api/v1/awards/9000111/summary")).isEqualTo(404);   // A's child: SYN-U-100
        } finally {
            revoke(g);
        }
        assertThat(status("nogrants", "/api/v1/awards/9000102/summary")).isEqualTo(403);       // nothing left
    }

    // --- Document Explorer and document search: Award and Proposal documents scoped ---------

    /** (module:documentNumber) for every row the Document Explorer returns, all pages. */
    private Set<String> explorerDocuments(String persona, String query) throws Exception {
        JsonNode page = body(persona, "/api/v1/documents?size=100" + query).get("results");
        assertThat(page.get("totalElements").asLong()).isEqualTo(page.get("content").size());
        Set<String> docs = new TreeSet<>();
        page.get("content").forEach(n -> docs.add(n.get("module").asText() + ":" + n.get("documentNumber").asText()));
        return docs;
    }

    private Set<String> searchDocuments(String persona, String query) throws Exception {
        JsonNode page = body(persona, "/api/documents/search?size=100" + query);
        assertThat(page.get("totalElements").asLong()).isEqualTo(page.get("content").size());
        Set<String> docs = new TreeSet<>();
        page.get("content").forEach(n -> docs.add(n.get("module").asText() + ":" + n.get("documentNumber").asText()));
        return docs;
    }

    private Map<String, Long> facets(String persona, String query) throws Exception {
        Map<String, Long> facets = new TreeMap<>();
        body(persona, "/api/v1/documents?size=100" + query).get("moduleFacets")
                .forEach(f -> facets.put(f.get("value").asText(), f.get("count").asLong()));
        return facets;
    }

    /** Every Award and Proposal document of the families the persona's own searches return. */
    private Set<String> documentsOfSearchableRecords(String persona) throws Exception {
        Set<String> awardFamilies = new TreeSet<>();
        body(persona, "/api/v1/awards/search?size=100").get("results").get("content")
                .forEach(n -> awardFamilies.add(n.get("awardNumber").asText()));
        Set<String> proposals = new TreeSet<>();
        body(persona, "/api/proposals/search?size=100").get("content")
                .forEach(n -> proposals.add(n.get("proposalNumber").asText()));
        Set<String> expected = new TreeSet<>();
        if (!awardFamilies.isEmpty()) {
            jdbc.sql("SELECT workflow_document_number FROM archive.award_version WHERE workflow_document_number IS NOT NULL "
                    + "AND award_number IN (:f)").param("f", awardFamilies).query(String.class).list()
                    .forEach(d -> expected.add("AWARD:" + d));
        }
        if (!proposals.isEmpty()) {
            jdbc.sql("SELECT document_number FROM archive.proposal_version WHERE document_number IS NOT NULL "
                    + "AND proposal_number IN (:p)").param("p", proposals).query(String.class).list()
                    .forEach(d -> expected.add("PROPOSAL:" + d));
        }
        return expected;
    }

    @Test
    void documentExplorerShowsExactlyTheCallersAwardAndProposalDocuments() throws Exception {
        // Pat: every version of A, C and D (family-wide, including the other-unit versions A' and C'),
        // and Proposal 2. Never A's child, Key-Person-only E, unrelated B, or Negotiation/Subaward.
        Set<String> pat = Set.of("AWARD:SYN-DOC-0101", "AWARD:SYN-DOC-0102", "AWARD:SYN-DOC-0103",
                "AWARD:SYN-DOC-0301", "AWARD:SYN-DOC-0302", "AWARD:SYN-DOC-0401", "PROPOSAL:SYN-PDOC-02");
        assertThat(explorerDocuments("pi", "")).isEqualTo(pat);             // default page (fast path)
        assertThat(explorerDocuments("pi", "&query=SYN")).isEqualTo(pat);   // filtered path
        assertThat(searchDocuments("pi", "")).isEqualTo(pat);               // /api/documents/search
        assertThat(facets("pi", "")).isEqualTo(Map.of("AWARD", 6L, "PROPOSAL", 1L));
        assertThat(facets("pi", "&query=SYN")).isEqualTo(Map.of("AWARD", 6L, "PROPOSAL", 1L));
        assertThat(body("pi", "/api/dashboard").get("documents").asLong()).isEqualTo(7);

        // The same scope as the caller's Award and Proposal searches, for every restricted persona.
        for (String persona : List.of("pi", "department", "oav", "multi")) {
            Set<String> expected = documentsOfSearchableRecords(persona);
            assertThat(explorerDocuments(persona, "")).as(persona).isEqualTo(expected);
            assertThat(explorerDocuments(persona, "&query=SYN")).as(persona).isEqualTo(expected);
            assertThat(searchDocuments(persona, "")).as(persona).isEqualTo(expected);
        }
        // IO = Award account: the IO viewer gets Award F's document and no Proposal documents.
        assertThat(explorerDocuments("oav", "")).isEqualTo(Set.of("AWARD:SYN-DOC-0601"));
        // No grant at all: not provisioned, so no document is listed (403, not an empty page).
        assertThat(status("nogrants", "/api/v1/documents")).isEqualTo(403);
        assertThat(status("nogrants", "/api/documents/search")).isEqualTo(403);
    }

    @Test
    void forbiddenDocumentsCannotBeFoundByNumberModuleOrFilter() throws Exception {
        for (String q : List.of("&documentNumber=SYN-DOC-0201", "&documentNumber=SYN-DOC-0111",
                "&documentNumber=SYN-DOC-0501", "&businessRecordNumber=990002-00001", "&module=NEGOTIATION",
                "&module=SUBAWARD", "&query=SYN-NDOC", "&query=SYN-SDOC", "&documentNumber=SYN-PDOC-01")) {
            assertThat(explorerDocuments("pi", q)).as(q).isEmpty();
            assertThat(facets("pi", q)).as(q).isEmpty();
        }
        for (String q : List.of("&documentNumber=SYN-DOC-0201", "&module=NEGOTIATION", "&module=SUBAWARD",
                "&module=IRB", "&documentNumber=SYN-NDOC-01", "&documentNumber=SYN-SDOC-01")) {
            assertThat(searchDocuments("pi", q)).as(q).isEmpty();
        }
        // Central keeps every module, including the Negotiation and Subaward documents.
        assertThat(explorerDocuments("central", "&query=SYN"))
                .contains("NEGOTIATION:SYN-NDOC-01", "SUBAWARD:SYN-SDOC-01", "AWARD:SYN-DOC-0201");
        assertThat(searchDocuments("central", "&documentNumber=SYN-NDOC-01")).containsExactly("NEGOTIATION:SYN-NDOC-01");
    }

    @Test
    void revokingTheGrantRemovesItsDocumentsOnTheNextRequest() throws Exception {
        long unit = grant("SYN-INST-0007", "UNIT", "SYN-U-500", false, null);       // Proposal 2's unit
        try {
            assertThat(explorerDocuments("nogrants", "")).containsExactly("PROPOSAL:SYN-PDOC-02");
        } finally {
            revoke(unit);
        }
        assertThat(status("nogrants", "/api/v1/documents")).isEqualTo(403);         // nothing left: not provisioned
    }

    // --- Archived File Finder: Award and Proposal files ---------------------------------------

    private Set<String> finderRows(String persona, String query) throws Exception {
        JsonNode page = body(persona, "/api/v1/attachments/search?size=100&" + query);
        assertThat(page.get("totalElements").asLong()).isEqualTo(page.get("content").size());
        Set<String> rows = new TreeSet<>();
        page.get("content").forEach(n -> rows.add(n.get("recordType").asText() + ":" + n.get("parentId").asLong()));
        return rows;
    }

    @Test
    void fileFinderReturnsTheCallersAwardAndProposalFilesOnly() throws Exception {
        // Family-wide: Pat sees the files on both A versions (A and the other-unit A').
        assertThat(finderRows("pi", "recordNumber=990001-00001")).containsExactly("AWARD:9000102", "AWARD:9000103");
        assertThat(finderRows("pi", "recordType=ALL&recordNumber=990001-00001"))
                .containsExactly("AWARD:9000102", "AWARD:9000103");
        assertThat(finderRows("pi", "recordType=PROPOSAL&recordNumber=SYN-PRP-0002")).containsExactly("PROPOSAL:8000201");
        assertThat(finderRows("pi", "recordType=ALL&recordNumber=SYN-PRP-0002")).containsExactly("PROPOSAL:8000201");
        // Not Pat's: Proposal 1 (related to Award A - a relationship never authorizes) and Award B.
        assertThat(finderRows("pi", "recordType=PROPOSAL&recordNumber=SYN-PRP-0001")).isEmpty();
        assertThat(finderRows("pi", "recordType=ALL&recordNumber=SYN-PRP-0001")).isEmpty();
        assertThat(finderRows("pi", "recordType=ALL&recordNumber=990002-00001")).isEmpty();
        assertThat(finderRows("pi", "recordType=NEGOTIATION&recordNumber=SYN-NDOC-01")).isEmpty();
        // The department sees Proposal 4's other-unit version file family-wide; the IO viewer no Proposal files.
        assertThat(finderRows("department", "recordType=PROPOSAL&recordNumber=SYN-PRP-0004")).containsExactly("PROPOSAL:8000402");
        assertThat(finderRows("oav", "recordType=ALL&recordNumber=SYN-PRP-0002")).isEmpty();
        // Central: everything.
        assertThat(finderRows("central", "recordType=ALL&recordNumber=SYN-PRP-0001")).containsExactly("PROPOSAL:8000101");
    }
}
