package edu.bu.archive.application.ai;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

import edu.bu.archive.adapter.in.web.dto.ai.AwardEvidenceResultResponse;
import edu.bu.archive.adapter.in.web.dto.ai.AwardEvidenceSearchResponse;
import edu.bu.archive.adapter.out.persistence.AwardArchiveRepository;
import edu.bu.archive.adapter.out.persistence.AwardEvidenceRetrievalRepository;
import edu.bu.archive.adapter.out.persistence.authorization.AwardAccountNumberIoResolver;
import edu.bu.archive.adapter.out.persistence.authorization.AwardAccountNumberIoSql;
import edu.bu.archive.adapter.out.persistence.authorization.JdbcRecordFactsRepository;
import edu.bu.archive.adapter.out.persistence.authorization.JdbcUnitHierarchy;
import edu.bu.archive.application.authorization.AccessGrant;
import edu.bu.archive.application.authorization.AccessScopeResolver;
import edu.bu.archive.application.authorization.AuthorizationPolicy;
import edu.bu.archive.application.authorization.AuthorizationProperties;
import edu.bu.archive.application.authorization.AwardHierarchy;
import edu.bu.archive.application.authorization.AwardRecordAccess;
import edu.bu.archive.application.authorization.ContactRelationships;
import edu.bu.archive.application.authorization.GrantType;
import edu.bu.archive.application.authorization.IdentityEnrollment;
import edu.bu.archive.application.authorization.IdentityLinkStatus;
import edu.bu.archive.application.authorization.IdentityResolver;
import edu.bu.archive.application.authorization.InstitutionalIdentifier;
import edu.bu.archive.application.authorization.RecordAuthorizationService;
import edu.bu.archive.application.authorization.ValidatedCognitoIdentity;
import edu.bu.archive.application.authorization.audit.ArchiveActivityRecorder;
import edu.bu.archive.application.authorization.fixtures.InMemoryAccessGrantRepository;
import edu.bu.archive.application.authorization.fixtures.InMemoryIdentityLinkRepository;
import edu.bu.archive.application.award.AwardArchiveService;
import edu.bu.archive.application.port.out.EmbeddingProvider;
import edu.bu.archive.config.SemanticSearchProperties;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.lang.reflect.Constructor;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Statement;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * QA finding H5 (2026-11-17): POST /api/ai/awards/{awardNumber}/evidence-search
 * returned RELATED_PROPOSAL / RELATED_NEGOTIATION / RELATED_SUBAWARD excerpts
 * for related records the caller could not open, because only the parent
 * Award was gated.
 *
 * <p>Everything runs through a REAL {@link RecordAuthorizationService} with
 * enforcement ON, the real {@link AwardArchiveService} parent gate, the real
 * {@link AwardEvidenceRetrievalRepository} against pgvector, and a
 * deterministic stub {@link EmbeddingProvider}. The service is constructed
 * reflectively (see {@link #service}) so this class compiles and runs
 * unchanged against the unfixed production code, which is how the defect was
 * proven red before the fix.
 *
 * <p>Fixtures are synthetic. No BU data appears here.
 *
 * <pre>
 * Units: 7777 (CHEMISTRY), 1234 (PHYSICS)
 * Awards: 1002 HUB-00001 lead 7777, account IO-HUB, FARRER is PI  (the searched Award)
 *         2001 OUT-00001 lead 1234, account IO-OUT
 * Proposals: 8001 P-IN  lead 7777, FARRER is PI
 *            8002 P-OUT lead 1234, "Secret oncology study"
 *   award_funding_proposal 601 HUB->P-IN, 602 HUB->P-OUT
 * Negotiations: 501 Award HUB-00001, lead unit 7777
 *               502 now Award OUT-00001, lead unit 1234 - its evidence row was
 *                   indexed under HUB-00001 before it was re-associated (the
 *                   evidence table is a derived index; it can lag the archive)
 * Subawards: 9101 SUB-A funded by HUB 1002 (funding 301)
 *            9102 SUB-B funding 302 names HUB-00001 but its award_id resolves
 *                   to nothing; its only real funding Award is OUT 2001 (303)
 *
 * Evidence (HUB-00001), distance from the query vector - the restricted rows
 * are deliberately the NEAREST, so they would win any top-K slot:
 *   RELATED_PROPOSAL    602 0.01   restricted for every non-Central caller
 *   RELATED_NEGOTIATION 502 0.02   restricted for every non-Central caller
 *   RELATED_SUBAWARD    302 0.03   restricted for every non-Central caller
 *   RELATED_PROPOSAL    601 0.20
 *   RELATED_SUBAWARD    301 0.22
 *   RELATED_NEGOTIATION 501 0.25
 *   AWARD_VERSION      1002 0.30
 * </pre>
 */
@Tag("database")
@Testcontainers(disabledWithoutDocker = true)
class AwardEvidenceSearchScopingIntegrationTest {

    private static final Pattern MIGRATION_VERSION = Pattern.compile("^V(\\d+)__.*\\.sql$");
    private static final Instant NOW = Instant.parse("2026-11-17T12:00:00Z");
    private static final String ISSUER = "https://idp.test.invalid/synthetic";
    private static final String AWARD = "HUB-00001";
    private static final String QUERY = "who funds this award and who negotiated it";
    private static final int DIMENSIONS = 1024;

    private static final List<String> RELATED_TYPES =
            List.of("RELATED_PROPOSAL", "RELATED_NEGOTIATION", "RELATED_SUBAWARD");

    /** Strings that exist ONLY in the restricted related records and their evidence rows. */
    private static final List<String> RESTRICTED_TOKENS = List.of(
            "P-OUT", "Secret oncology", "NDOC-502", "Secret Negotiator", "SUB-B", "SDOC-9102");

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg17").asCompatibleSubstituteFor("postgres"));

    private static JdbcClient jdbc;
    private static AwardArchiveRepository awardRepository;
    private static AwardEvidenceRetrievalRepository evidenceRepository;
    private static JdbcRecordFactsRepository facts;
    private static JdbcUnitHierarchy unitHierarchy;

    private record Caller(String name, String personId, List<AccessGrantSpec> grants, boolean enforced) {
        @Override
        public String toString() {
            return name;
        }
    }

    private record AccessGrantSpec(GrantType type, String unit, String ioValue, String awardNumber) { }

    private static final Caller DEPARTMENT = new Caller("DEPARTMENT", null,
            List.of(new AccessGrantSpec(GrantType.UNIT, "7777", null, null)), true);
    private static final Caller PI = new Caller("PI", "P-FARRER",
            List.of(new AccessGrantSpec(GrantType.CONTACT_DERIVATION, null, null, null)), true);
    private static final Caller IO = new Caller("IO", null,
            List.of(new AccessGrantSpec(GrantType.IO, null, "IO-HUB", null)), true);
    private static final Caller PARENT_AWARD = new Caller("PARENT_AWARD", null,
            List.of(new AccessGrantSpec(GrantType.PARENT_AWARD, null, null, AWARD)), true);
    private static final Caller CENTRAL = new Caller("CENTRAL", null,
            List.of(new AccessGrantSpec(GrantType.CENTRAL, null, null, null)), true);
    /** Enforcement OFF (the deployed default). Same grant as DEPARTMENT, which must not matter. */
    private static final Caller OFF = new Caller("OFF", null,
            List.of(new AccessGrantSpec(GrantType.UNIT, "7777", null, null)), false);

    private final List<String> embeddedTexts = Collections.synchronizedList(new ArrayList<>());

    @BeforeAll
    static void applyMigrationsAndSeed() throws Exception {
        Path migrationsDir = locateMigrationsDirectory();
        List<Path> migrations;
        try (Stream<Path> files = Files.list(migrationsDir)) {
            migrations = files
                    .filter(path -> MIGRATION_VERSION.matcher(path.getFileName().toString()).matches())
                    .sorted(Comparator.comparingInt(AwardEvidenceSearchScopingIntegrationTest::migrationVersion))
                    .toList();
        }

        SimpleDriverDataSource dataSource = new SimpleDriverDataSource();
        dataSource.setDriverClass(org.postgresql.Driver.class);
        dataSource.setUrl(postgres.getJdbcUrl());
        dataSource.setUsername(postgres.getUsername());
        dataSource.setPassword(postgres.getPassword());

        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement()) {
            for (Path migration : migrations) {
                statement.execute(Files.readString(migration));
            }
            statement.execute("""
                    INSERT INTO archive.unit (unit_number, unit_name, parent_unit_number) VALUES
                    ('7777', 'CHEMISTRY', NULL),
                    ('1234', 'PHYSICS',   NULL)
                    """);
            statement.execute("""
                    INSERT INTO archive.award_version (
                        award_id, award_number, sequence_number, title, status_description,
                        sponsor_code, sponsor_name, lead_unit_number, lead_unit_name,
                        account_number, is_primary_current, workflow_document_number
                    ) VALUES
                    (1002, 'HUB-00001', 1, 'Hub award',        'Active', '0200', 'NIH', '7777', 'CHEMISTRY', 'IO-HUB', TRUE, 'DOC-HUB-1'),
                    (2001, 'OUT-00001', 1, 'Secret out award', 'Active', '0100', 'NSF', '1234', 'PHYSICS',   'IO-OUT', TRUE, 'DOC-OUT-1')
                    """);
            statement.execute("""
                    INSERT INTO archive.award_person (
                        award_person_id, award_id, award_number, sequence_number,
                        full_name, contact_role_code, person_id
                    ) VALUES
                    (101, 1002, 'HUB-00001', 1, 'L FARRER', 'PI', 'P-FARRER')
                    """);
            statement.execute("""
                    INSERT INTO archive.proposal_version (
                        proposal_id, proposal_number, version_number, title,
                        proposal_sequence_status, sponsor_code, sponsor_name,
                        lead_unit_number, lead_unit_name, principal_investigator_name
                    ) VALUES
                    (8001, 'P-IN',  1, 'Visible chemistry proposal', 'ACTIVE', '0200', 'NIH', '7777', 'CHEMISTRY', 'L FARRER'),
                    (8002, 'P-OUT', 1, 'Secret oncology study',      'ACTIVE', '0100', 'NSF', '1234', 'PHYSICS',   'DR RESTRICTED')
                    """);
            statement.execute("""
                    INSERT INTO archive.proposal_person (
                        proposal_person_id, proposal_id, proposal_number,
                        sequence_number, person_id, full_name, contact_role_code
                    ) VALUES
                    (201, 8001, 'P-IN', 1, 'P-FARRER', 'L FARRER', 'PI')
                    """);
            statement.execute("""
                    INSERT INTO archive.award_funding_proposal (
                        award_funding_proposal_id, award_id, proposal_id, active_flag
                    ) VALUES
                    (601, 1002, 8001, 'Y'),
                    (602, 1002, 8002, 'Y')
                    """);
            statement.execute("""
                    INSERT INTO archive.subaward (
                        subaward_id, subaward_code, sequence_number, document_number,
                        title, status_description, subaward_sequence_status,
                        organization_id, account_number, requisitioner_unit
                    ) VALUES
                    (9101, 'SUB-A', 1, 'SDOC-9101', 'Sub A', 'Active', 'ACTIVE', 'ORG-A', 'ACC-A', '7777'),
                    (9102, 'SUB-B', 1, 'SDOC-9102', 'Sub B', 'Active', 'ACTIVE', 'ORG-B', 'ACC-B', '1234')
                    """);
            statement.execute("""
                    INSERT INTO archive.subaward_funding (
                        subaward_funding_source_id, subaward_id, subaward_code,
                        sequence_number, award_id, award_number
                    ) VALUES
                    (301, 9101, 'SUB-A', 1, 1002, 'HUB-00001'),
                    (302, 9102, 'SUB-B', 1, NULL, 'HUB-00001'),
                    (303, 9102, 'SUB-B', 1, 2001, 'OUT-00001')
                    """);
            statement.execute("""
                    INSERT INTO archive.negotiation (
                        negotiation_id, document_number,
                        negotiation_association_type_code,
                        negotiation_association_type_description,
                        associated_document_id, negotiator_person_id, negotiator_full_name
                    ) VALUES
                    (501, 'NDOC-501', 'AWD', 'Award', 'HUB-00001', 'P-NEG', 'Visible Negotiator'),
                    (502, 'NDOC-502', 'AWD', 'Award', 'OUT-00001', 'P-NEG', 'Secret Negotiator')
                    """);
            statement.execute("""
                    INSERT INTO archive.negotiation_search_attribute (
                        negotiation_id, attribute_source, title, lead_unit_number, lead_unit_name
                    ) VALUES
                    (501, 'AWARD', 'Hub negotiation',    '7777', 'CHEMISTRY'),
                    (502, 'AWARD', 'Secret negotiation', '1234', 'PHYSICS')
                    """);
        }

        jdbc = JdbcClient.create(dataSource);
        seedEvidence("RELATED_PROPOSAL", 602, "archive.award_funding_proposal", 0.01,
                "Award HUB-00001 version 1 is funded by Proposal P-OUT: Secret oncology study.");
        seedEvidence("RELATED_NEGOTIATION", 502, "archive.negotiation", 0.02,
                "Negotiation NDOC-502 (Clinical Trial Agreement) associated with Award HUB-00001, "
                        + "negotiator Secret Negotiator, status In Progress.");
        seedEvidence("RELATED_SUBAWARD", 302, "archive.subaward_funding", 0.03,
                "Subaward SUB-B (document SDOC-9102) is linked to Award HUB-00001, status Active.");
        seedEvidence("RELATED_PROPOSAL", 601, "archive.award_funding_proposal", 0.20,
                "Award HUB-00001 version 1 is funded by Proposal P-IN: Visible chemistry proposal.");
        seedEvidence("RELATED_SUBAWARD", 301, "archive.subaward_funding", 0.22,
                "Subaward SUB-A (document SDOC-9101) is linked to Award HUB-00001, status Active.");
        seedEvidence("RELATED_NEGOTIATION", 501, "archive.negotiation", 0.25,
                "Negotiation NDOC-501 (Contract) associated with Award HUB-00001, "
                        + "negotiator Visible Negotiator, status Open.");
        seedEvidence("AWARD_VERSION", 1002, "archive.award_version", 0.30,
                "Award HUB-00001 version 1 (document DOC-HUB-1): Hub award. Sponsor: NIH.");

        awardRepository = new AwardArchiveRepository(jdbc);
        evidenceRepository = new AwardEvidenceRetrievalRepository(jdbc);
        facts = new JdbcRecordFactsRepository(jdbc, new AwardAccountNumberIoResolver(jdbc));
        unitHierarchy = new JdbcUnitHierarchy(jdbc);
    }

    @AfterEach
    void clearRequest() {
        RequestContextHolder.resetRequestAttributes();
    }

    // =====================================================================
    // Per related type
    // =====================================================================

    @Test
    void evidenceSearchOmitsUnauthorizedRelatedProposal() {
        for (Caller caller : List.of(DEPARTMENT, PI)) {
            List<String> keys = keys(search(caller, List.of("RELATED_PROPOSAL"), 20));
            assertThat(keys)
                    .as("%s may open P-IN (lead unit 7777 / PI on it) but not P-OUT; "
                            + "expected RELATED_PROPOSAL evidence 602 (P-OUT) to be omitted", caller)
                    .containsExactly("RELATED_PROPOSAL:601");
        }
        for (Caller caller : List.of(IO, PARENT_AWARD)) {
            assertThat(keys(search(caller, List.of("RELATED_PROPOSAL"), 20)))
                    .as("%s has no Proposal rule (IO: none approved; PARENT_AWARD: Award only, SEC-005); "
                            + "expected every RELATED_PROPOSAL row to be omitted", caller)
                    .isEmpty();
        }
    }

    @Test
    void evidenceSearchOmitsUnauthorizedRelatedNegotiation() {
        for (Caller caller : List.of(DEPARTMENT, PI)) {
            assertThat(keys(search(caller, List.of("RELATED_NEGOTIATION"), 20)))
                    .as("%s may open Negotiation 501 but not 502 (lead unit 1234, associated with "
                            + "OUT-00001); expected RELATED_NEGOTIATION evidence 502 to be omitted", caller)
                    .containsExactly("RELATED_NEGOTIATION:501");
        }
        // F1 (owner decision 2026-11-18): IO joins PARENT_AWARD here. Both reach
        // Award records only; neither is inherited by a related Negotiation.
        for (Caller caller : List.of(PARENT_AWARD, IO)) {
            assertThat(keys(search(caller, List.of("RELATED_NEGOTIATION"), 20)))
                    .as("%s reaches Award records only (PARENT_AWARD: SEC-005; IO: F1); "
                            + "expected every RELATED_NEGOTIATION row to be omitted", caller)
                    .isEmpty();
        }
    }

    @Test
    void evidenceSearchOmitsUnauthorizedRelatedSubaward() {
        for (Caller caller : List.of(DEPARTMENT, PI)) {
            assertThat(keys(search(caller, List.of("RELATED_SUBAWARD"), 20)))
                    .as("%s may open SUB-A (funded by HUB) but not SUB-B (its only resolvable funding "
                            + "Award is OUT-00001); expected RELATED_SUBAWARD evidence 302 to be omitted", caller)
                    .containsExactly("RELATED_SUBAWARD:301");
        }
        // F1 (owner decision 2026-11-18): an IO grant is never inherited by a
        // Subaward either - the documented IO boundary, defect found live in A8.
        for (Caller caller : List.of(PARENT_AWARD, IO)) {
            assertThat(keys(search(caller, List.of("RELATED_SUBAWARD"), 20)))
                    .as("%s is never inherited by a Subaward (PARENT_AWARD: SEC-005; IO: F1); "
                            + "expected every RELATED_SUBAWARD row to be omitted", caller)
                    .isEmpty();
        }
    }

    // =====================================================================
    // Per caller, all types
    // =====================================================================

    @Test
    void departmentCallerSeesOnlyAuthorizedEvidence() {
        assertThat(keys(search(DEPARTMENT, null, 20)))
                .as("DEPARTMENT (UNIT 7777): expected only authorized evidence, nearest first")
                .containsExactly("RELATED_PROPOSAL:601", "RELATED_SUBAWARD:301",
                        "RELATED_NEGOTIATION:501", "AWARD_VERSION:1002");
    }

    @Test
    void piCallerSeesOnlyAuthorizedEvidence() {
        assertThat(keys(search(PI, null, 20)))
                .as("PI (CONTACT_DERIVATION, roles PI/MPI/COI): expected only authorized evidence")
                .containsExactly("RELATED_PROPOSAL:601", "RELATED_SUBAWARD:301",
                        "RELATED_NEGOTIATION:501", "AWARD_VERSION:1002");
    }

    @Test
    void ioCallerSeesOnlyAuthorizedEvidence() {
        assertThat(keys(search(IO, null, 20)))
                .as("IO (account IO-HUB) reaches Awards only - no Proposal rule, and since F1 "
                        + "(2026-11-18) no Subaward or Negotiation inheritance; expected the Award's own evidence")
                .containsExactly("AWARD_VERSION:1002");
    }

    @Test
    void parentAwardCallerSeesOnlyTheAwardsOwnEvidence() {
        assertThat(keys(search(PARENT_AWARD, null, 20)))
                .as("PARENT_AWARD (HUB-00001): reaches the Award only; expected no RELATED_* evidence")
                .containsExactly("AWARD_VERSION:1002");

        AwardEvidenceSearchResponse relatedOnly = search(PARENT_AWARD, RELATED_TYPES, 20);
        assertThat(relatedOnly.results())
                .as("PARENT_AWARD asking only for related evidence: expected no results")
                .isEmpty();
        assertThat(relatedOnly.insufficientEvidence())
                .as("insufficientEvidence must not reveal that unauthorized related evidence exists")
                .isTrue();
    }

    @Test
    void centralSeesAllRelatedEvidence() {
        assertThat(keys(search(CENTRAL, null, 20)))
                .containsExactly("RELATED_PROPOSAL:602", "RELATED_NEGOTIATION:502", "RELATED_SUBAWARD:302",
                        "RELATED_PROPOSAL:601", "RELATED_SUBAWARD:301", "RELATED_NEGOTIATION:501",
                        "AWARD_VERSION:1002");
    }

    @Test
    void enforcementOffIsUnchanged() {
        // The pre-H5 service: no record access at all (PERMIT_ALL), which is
        // byte-for-byte what every caller got before the fix.
        AwardEvidenceSearchResponse baseline = as(OFF, access -> unscopedService().search(AWARD, QUERY, null, 20));
        AwardEvidenceSearchResponse off = search(OFF, null, 20);
        AwardEvidenceSearchResponse central = search(CENTRAL, null, 20);

        assertThat(baseline.results()).hasSize(7);
        assertThat(off.results()).isEqualTo(baseline.results());
        assertThat(off.insufficientEvidence()).isEqualTo(baseline.insufficientEvidence());
        assertThat(off.awardNumber()).isEqualTo(baseline.awardNumber());
        assertThat(off.query()).isEqualTo(baseline.query());
        assertThat(central.results()).isEqualTo(baseline.results());

        for (List<String> types : List.of(List.of("RELATED_PROPOSAL"), RELATED_TYPES, List.of("AWARD_VERSION"))) {
            for (int topK : List.of(1, 3, 20)) {
                AwardEvidenceSearchResponse before =
                        as(OFF, access -> unscopedService().search(AWARD, QUERY, types, topK));
                assertThat(search(OFF, types, topK).results())
                        .as("enforcement OFF, types=%s topK=%d", types, topK)
                        .isEqualTo(before.results());
            }
        }
    }

    // =====================================================================
    // Ranking, response fields, model input and logs
    // =====================================================================

    @Test
    void rankingAndTopKApplyAfterAuthorization() {
        // The three restricted rows are the three NEAREST. A post-filter of a
        // top-3 result would leave DEPARTMENT with nothing; the predicate in
        // the same WHERE leaves the three nearest AUTHORIZED rows, in order.
        AwardEvidenceSearchResponse top3 = search(DEPARTMENT, null, 3);
        assertThat(keys(top3))
                .as("DEPARTMENT top-3: expected the 3 nearest authorized rows, ranked after authorization")
                .containsExactly("RELATED_PROPOSAL:601", "RELATED_SUBAWARD:301", "RELATED_NEGOTIATION:501");
        assertThat(top3.insufficientEvidence()).isFalse();

        AwardEvidenceSearchResponse top1 = search(DEPARTMENT, List.of("RELATED_PROPOSAL"), 1);
        assertThat(keys(top1))
                .as("DEPARTMENT top-1 Proposal: expected P-IN (601), not the nearer P-OUT (602)")
                .containsExactly("RELATED_PROPOSAL:601");

        assertThat(top3.results())
                .extracting(AwardEvidenceResultResponse::score)
                .as("scores must be descending, i.e. ORDER BY distance over authorized rows")
                .isSortedAccordingTo(Comparator.reverseOrder());
    }

    @Test
    void noResponseFieldExposesAnUnauthorizedRecord() {
        for (Caller caller : List.of(DEPARTMENT, PI, IO, PARENT_AWARD)) {
            AwardEvidenceSearchResponse response = search(caller, null, 20);
            String whole = response.toString();

            for (String token : RESTRICTED_TOKENS) {
                assertThat(whole).as("%s response must not contain %s", caller, token).doesNotContain(token);
            }
            for (AwardEvidenceResultResponse result : response.results()) {
                String key = result.documentType() + ":" + result.sourcePrimaryKey();
                assertThat(key)
                        .as("%s: sourcePrimaryKey/documentType must not identify a restricted record", caller)
                        .isNotIn("RELATED_PROPOSAL:602", "RELATED_NEGOTIATION:502", "RELATED_SUBAWARD:302");
                assertThat(result.awardNumber()).as("%s: awardNumber", caller).isEqualTo(AWARD);
                for (String token : RESTRICTED_TOKENS) {
                    assertThat(result.excerpt()).as("%s: excerpt", caller).doesNotContain(token);
                    assertThat(result.title()).as("%s: title", caller).doesNotContain(token);
                    assertThat(result.sourceTable()).as("%s: sourceTable", caller).doesNotContain(token);
                    assertThat(result.targetSection()).as("%s: targetSection", caller).doesNotContain(token);
                }
            }
            // documentType / targetSection / title metadata: one entry per
            // AUTHORIZED row only, so their counts cannot reveal a hidden one.
            long relatedProposalRows = response.results().stream()
                    .filter(r -> r.documentType().equals("RELATED_PROPOSAL")).count();
            long fundingProposalSections = response.results().stream()
                    .filter(r -> "fundingProposals".equals(r.targetSection())).count();
            assertThat(fundingProposalSections).as("%s: targetSection count", caller)
                    .isEqualTo(relatedProposalRows)
                    .isLessThanOrEqualTo(1);
            assertThat(response.results().stream()
                    .filter(r -> r.documentType().equals("RELATED_NEGOTIATION")).count())
                    .as("%s: RELATED_NEGOTIATION count", caller).isLessThanOrEqualTo(1);
            assertThat(response.results().stream()
                    .filter(r -> r.documentType().equals("RELATED_SUBAWARD")).count())
                    .as("%s: RELATED_SUBAWARD count", caller).isLessThanOrEqualTo(1);
            // correlationId is a random UUID - never a record identifier.
            assertThat(response.correlationId())
                    .as("%s: correlationId", caller)
                    .matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
            assertThat(response.insufficientEvidence())
                    .as("%s: insufficientEvidence reflects authorized rows only", caller)
                    .isEqualTo(response.results().isEmpty());
            assertThat(response.query()).isEqualTo(QUERY);
            assertThat(response.awardNumber()).isEqualTo(AWARD);
        }
    }

    @Test
    void embeddingProviderReceivesOnlyTheQuery() {
        embeddedTexts.clear();
        for (Caller caller : List.of(DEPARTMENT, PI, IO, PARENT_AWARD, CENTRAL, OFF)) {
            search(caller, null, 20);
        }
        assertThat(embeddedTexts)
                .as("the embedding model must receive the caller's query and nothing else")
                .hasSize(6)
                .containsOnly(QUERY);
    }

    @Test
    void noEvidenceTextIsLogged() {
        Logger root = (Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
        Logger app = (Logger) LoggerFactory.getLogger("edu.bu.archive");
        Logger jdbcLogger = (Logger) LoggerFactory.getLogger("org.springframework.jdbc");
        Level appLevel = app.getLevel();
        Level jdbcLevel = jdbcLogger.getLevel();
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        root.addAppender(appender);
        app.setLevel(Level.TRACE);
        jdbcLogger.setLevel(Level.TRACE);
        try {
            for (Caller caller : List.of(DEPARTMENT, PI, IO, PARENT_AWARD, CENTRAL)) {
                search(caller, null, 20);
            }
        } finally {
            root.detachAppender(appender);
            app.setLevel(appLevel);
            jdbcLogger.setLevel(jdbcLevel);
        }

        List<String> logged = new ArrayList<>();
        for (ILoggingEvent event : appender.list) {
            logged.add(event.getFormattedMessage());
            if (event.getThrowableProxy() != null) {
                logged.add(event.getThrowableProxy().getMessage());
            }
        }
        String all = String.join("\n", logged);
        for (String token : RESTRICTED_TOKENS) {
            assertThat(all).as("logs must not contain restricted %s", token).doesNotContain(token);
        }
        for (String excerptFragment : List.of("is funded by Proposal", "Visible chemistry proposal",
                "Visible Negotiator", "is linked to Award", "Hub award. Sponsor")) {
            assertThat(all).as("logs must not contain evidence text %s", excerptFragment)
                    .doesNotContain(excerptFragment);
        }
        assertThat(all).as("logs must not contain the raw query").doesNotContain(QUERY);
    }

    // --- harness -----------------------------------------------------------

    private AwardEvidenceSearchResponse search(Caller caller, List<String> types, int topK) {
        return as(caller, access -> service(access).search(AWARD, QUERY, types, topK));
    }

    private static List<String> keys(AwardEvidenceSearchResponse response) {
        return response.results().stream()
                .map(r -> r.documentType() + ":" + r.sourcePrimaryKey())
                .toList();
    }

    private EmbeddingProvider stubEmbeddings() {
        return text -> {
            embeddedTexts.add(text);
            float[] vector = new float[DIMENSIONS];
            vector[0] = 1f;
            return vector;
        };
    }

    /**
     * The service with the caller's record access wired the way Spring wires
     * it. Reflective so this class compiles against the pre-H5 service, which
     * had no record-access constructor: there it falls back to the only
     * constructor that existed, i.e. the unfixed behaviour under test.
     */
    private AwardEvidenceSearchService service(RecordAuthorizationService access) {
        AwardArchiveService awards = new AwardArchiveService(awardRepository, null, access);
        for (Constructor<?> constructor : AwardEvidenceSearchService.class.getConstructors()) {
            Class<?>[] types = constructor.getParameterTypes();
            if (types.length == 7 && types[6] == AwardRecordAccess.class) {
                try {
                    return (AwardEvidenceSearchService) constructor.newInstance(
                            awards, evidenceRepository, stubEmbeddings(), new SensitiveFieldRedactor(),
                            new SemanticSearchProperties(), ArchiveActivityRecorder.disabled(Clock.systemUTC()),
                            access);
                } catch (ReflectiveOperationException e) {
                    throw new IllegalStateException(e);
                }
            }
        }
        return new AwardEvidenceSearchService(awards, evidenceRepository, stubEmbeddings(),
                new SensitiveFieldRedactor(), new SemanticSearchProperties());
    }

    /** No record access at all: the behaviour every caller had before H5. */
    private AwardEvidenceSearchService unscopedService() {
        return new AwardEvidenceSearchService(new AwardArchiveService(awardRepository, null),
                evidenceRepository, stubEmbeddings(), new SensitiveFieldRedactor(), new SemanticSearchProperties());
    }

    private static <T> T as(Caller caller, Function<RecordAuthorizationService, T> work) {
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(new MockHttpServletRequest()));
        try {
            return work.apply(authorization(caller));
        } finally {
            RequestContextHolder.resetRequestAttributes();
        }
    }

    private static RecordAuthorizationService authorization(Caller caller) {
        String institutionalId = "SYN-INST-" + caller.name();
        var identity = new ValidatedCognitoIdentity(ISSUER, "syn-sub-" + caller.name(), "syn-" + caller.name(), NOW);
        var links = new InMemoryIdentityLinkRepository();
        links.link(identity, institutionalId, caller.personId(), "syn-" + caller.name(), IdentityLinkStatus.ACTIVE);
        var grants = new InMemoryAccessGrantRepository();
        long id = 1;
        for (AccessGrantSpec spec : caller.grants()) {
            grants.add(new AccessGrant(id++, new InstitutionalIdentifier(institutionalId), spec.type(),
                    spec.unit(), false, spec.ioValue(), null, null, null, spec.awardNumber()));
        }

        var properties = new AuthorizationProperties();
        properties.setEnforcementEnabled(caller.enforced());
        properties.setVersionScope(AuthorizationPolicy.VersionScope.FAMILY_WIDE);
        properties.setDepartmentMatch(AuthorizationPolicy.DepartmentMatch.EXACT_LEAD_UNIT);
        properties.setResearchStaffRoles(Set.of("PI", "MPI", "COI"));
        properties.setContactDerivation(AuthorizationPolicy.ContactDerivation.EXPLICIT_GRANT);
        properties.setMaxSignInAge(Duration.ofHours(8));

        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        return new RecordAuthorizationService(
                properties,
                () -> Optional.of(identity),
                new IdentityResolver(links),
                new AccessScopeResolver(grants, clock, properties::policy, ContactRelationships.NONE,
                        AwardHierarchy.SELF_ONLY),
                facts,
                unitHierarchy,
                unitHierarchy,
                new AwardAccountNumberIoSql(),
                IdentityEnrollment.DISABLED,
                clock);
    }

    /** One evidence row at cosine distance {@code distance} from the stub query vector (e0). */
    private static void seedEvidence(String documentType, long recordId, String sourceTable,
                                     double distance, String text) {
        double cos = 1.0 - distance;
        double sin = Math.sqrt(1.0 - cos * cos);
        StringBuilder vector = new StringBuilder("[").append(cos).append(',').append(sin);
        for (int i = 2; i < DIMENSIONS; i++) {
            vector.append(",0");
        }
        vector.append(']');
        jdbc.sql("""
                INSERT INTO archive.search_embedding (
                    module, record_id, canonical_family_id, business_number, source_text,
                    source_hash, embedding, embedding_model, document_type, parent_module,
                    parent_business_identifier, exact_record_id, source_table, source_primary_key
                ) VALUES (
                    'AWARD', :id, 1002, 'HUB-00001', :text,
                    md5(:text), CAST(:vector AS vector), 'stub', :type, 'AWARD',
                    'HUB-00001', :id, :table, :id
                )
                """)
                .param("id", recordId)
                .param("text", text)
                .param("vector", vector.toString())
                .param("type", documentType)
                .param("table", sourceTable)
                .update();
    }

    private static int migrationVersion(Path path) {
        Matcher matcher = MIGRATION_VERSION.matcher(path.getFileName().toString());
        return matcher.matches() ? Integer.parseInt(matcher.group(1)) : Integer.MAX_VALUE;
    }

    private static Path locateMigrationsDirectory() {
        Path candidate = Path.of("database", "migrations");
        if (Files.isDirectory(candidate)) {
            return candidate;
        }
        return Path.of("..", "database", "migrations");
    }
}
