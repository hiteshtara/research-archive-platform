package edu.bu.archive.adapter.out.persistence.authorization;

import edu.bu.archive.adapter.in.web.dto.PageResponse;
import edu.bu.archive.adapter.in.web.dto.award.AwardSapTransmissionChildResponse;
import edu.bu.archive.adapter.in.web.dto.award.AwardSapTransmissionResponse;
import edu.bu.archive.adapter.in.web.dto.award.TimeAndMoneyDocumentResponse;
import edu.bu.archive.adapter.in.web.dto.award.TimeAndMoneyTransactionDetailResponse;
import edu.bu.archive.adapter.in.web.dto.award.TimeAndMoneyTransactionResponse;
import edu.bu.archive.adapter.in.web.dto.negotiation.NegotiationAssociatedRecordResponse;
import edu.bu.archive.adapter.in.web.dto.negotiation.NegotiationRowResponse;
import edu.bu.archive.adapter.in.web.dto.negotiation.NegotiationSummaryResponse;
import edu.bu.archive.adapter.in.web.dto.negotiation.NegotiationWorkspaceResponse;
import edu.bu.archive.adapter.out.persistence.AwardArchiveRepository;
import edu.bu.archive.adapter.out.persistence.NegotiationArchiveRepository;
import edu.bu.archive.application.authorization.AccessGrant;
import edu.bu.archive.application.authorization.AccessScopeResolver;
import edu.bu.archive.application.authorization.AuthorizationPolicy;
import edu.bu.archive.application.authorization.AuthorizationProperties;
import edu.bu.archive.application.authorization.ContactRelationships;
import edu.bu.archive.application.authorization.GrantType;
import edu.bu.archive.application.authorization.IdentityEnrollment;
import edu.bu.archive.application.authorization.IdentityLinkStatus;
import edu.bu.archive.application.authorization.IdentityResolver;
import edu.bu.archive.application.authorization.InstitutionalIdentifier;
import edu.bu.archive.application.authorization.NegotiationRecordAccess;
import edu.bu.archive.application.authorization.RecordAuthorizationService;
import edu.bu.archive.application.authorization.ValidatedCognitoIdentity;
import edu.bu.archive.application.authorization.fixtures.InMemoryAccessGrantRepository;
import edu.bu.archive.application.authorization.fixtures.InMemoryIdentityLinkRepository;
import edu.bu.archive.application.award.AwardArchiveService;
import edu.bu.archive.application.award.report.AwardReportData;
import edu.bu.archive.application.award.report.AwardReportService;
import edu.bu.archive.application.negotiation.NegotiationArchiveService;
import edu.bu.archive.application.negotiation.NegotiationSearchFilters;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Statement;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Defect B4 (2026-11-17): three disclosures past the record gate.
 *
 * <ul>
 *   <li><b>H1</b> - GET /api/v1/awards/{awardId}/time-and-money/transactions/{id}
 *       and .../documents/{documentNumber} gated the Award, then fetched the
 *       transaction / document by its own key alone: any caller who could
 *       open ONE Award could read every Time and Money record in the archive
 *       (an IDOR). Transaction rows also named OTHER Awards.</li>
 *   <li><b>H2</b> - Negotiation list and workspace rows returned the
 *       identifier and the Award-resolved attributes of an associated record
 *       the caller cannot see; search matched on that identifier (free text
 *       and the associationId filter), so award/proposal numbers could be
 *       probed; and the redacted /associated-record answer still said
 *       'AWD'/'Award'.</li>
 *   <li><b>H3</b> - GET /api/v1/awards/{awardId}/sap-transmissions returned
 *       every hierarchy child in the transmission (other Award families) and
 *       the raw SOAP payloads, which embed them. The Award Report PDF reads
 *       the same method.</li>
 * </ul>
 *
 * <p>Runs through a REAL {@link RecordAuthorizationService} wired as
 * production wires it - the account-number IO resolver and IO SQL strategy,
 * and the real {@link JdbcAwardHierarchy} for PARENT_AWARD - with enforcement
 * ON, real facts from PostgreSQL and real grants. Every scoped grant type is
 * exercised: DEPARTMENT (UNIT), PI (contact derivation), IO, PARENT_AWARD,
 * plus CENTRAL and enforcement OFF. The tests call only methods that existed
 * before the fix, so the class compiles and runs unchanged against the
 * unfixed production code - which is how the defects were proven red.
 *
 * <p><b>H2 and scope types.</b> A Negotiation is reached through its lead
 * unit OR through the very association being hidden (Award / Proposal
 * inheritance; PARENT_AWARD is never inherited, SEC-005). So "Negotiation
 * visible, associated record hidden" can only arise through a UNIT grant -
 * DEPT. For PI, IO and PARENT_AWARD the H2 tests still run and assert that
 * nothing hidden is listed, searchable, or disagrees with the per-record
 * decision; they are regression guards rather than red-on-main proofs.
 *
 * <p>Fixtures are synthetic. No BU data appears here.
 *
 * <pre>
 * Units: 7777 (CHEMISTRY), 1234 (PHYSICS)
 *
 * Awards (award_id number lead account, all current)
 *   1000 TOP-00000   1234 ACC-TOP     hierarchy root (hidden)
 *   1001 ROOT-00001  7777 ACC-ROOT    child of TOP; P-PI is PI
 *   2001 KID-00002   1234 ACC-KID     child of TOP (hidden)
 *   3001 OTHER-00001 1234 ACC-OTHER   unrelated (hidden)
 *   4001 SIB-00003   7777 ACC-SIB     child of ROOT; P-PI is PI
 *
 * Time and Money
 *   txn 900 / TM-ROOT  ROOT-00001 -> KID-00002 (details: ROOT->SIB, ROOT->KID)
 *   txn 999 / TM-OTHER OTHER-00001 only
 * SAP transmission 51 on 1001, children ROOT, KID, SIB; payload names KID.
 *
 * Proposals: 8001 P-IN (7777), 8002 P-OUT (1234)
 * Negotiations (all lead unit 7777)
 *   701 AWD KID-00002  (hidden; attributes resolved FROM it)
 *   702 AWD ROOT-00001 (visible)
 *   703 NO  NDOC-703   (genuinely unassociated)
 *   704 IP  P-OUT      (hidden proposal)
 *
 * Who sees which Award
 *   DEPT   UNIT 7777               ROOT, SIB
 *   PI     contact P-PI            ROOT, SIB
 *   IO     IO ACC-ROOT             ROOT
 *   PARENT PARENT_AWARD ROOT-00001 ROOT, SIB
 * </pre>
 */
@Tag("database")
@Testcontainers(disabledWithoutDocker = true)
class TimeAndMoneySapNegotiationScopingIntegrationTest {

    private static final Pattern MIGRATION_VERSION = Pattern.compile("^V(\\d+)__.*\\.sql$");
    private static final Instant NOW = Instant.parse("2026-11-17T12:00:00Z");
    private static final String ISSUER = "https://idp.test.invalid/synthetic";

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg17").asCompatibleSubstituteFor("postgres"));

    private static JdbcClient jdbc;
    private static AwardArchiveRepository awardRepository;
    private static NegotiationArchiveRepository negotiationRepository;
    private static JdbcRecordFactsRepository facts;
    private static JdbcUnitHierarchy unitHierarchy;

    /** A synthetic caller and the Award numbers it is expected to see. */
    private record Caller(String name, String personId, List<GrantSpec> grants, boolean enforced,
                          List<String> visibleAwards) {
        @Override
        public String toString() {
            return name;
        }
    }

    private record GrantSpec(GrantType type, String unit, String io, String awardNumber) { }

    private static final Caller DEPT = new Caller("DEPT", null,
            List.of(new GrantSpec(GrantType.UNIT, "7777", null, null)), true,
            List.of("ROOT-00001", "SIB-00003"));
    private static final Caller PI = new Caller("PI", "P-PI",
            List.of(new GrantSpec(GrantType.CONTACT_DERIVATION, null, null, null)), true,
            List.of("ROOT-00001", "SIB-00003"));
    private static final Caller IO = new Caller("IO", null,
            List.of(new GrantSpec(GrantType.IO, null, "ACC-ROOT", null)), true,
            List.of("ROOT-00001"));
    private static final Caller PARENT = new Caller("PARENT", null,
            List.of(new GrantSpec(GrantType.PARENT_AWARD, null, null, "ROOT-00001")), true,
            List.of("ROOT-00001", "SIB-00003"));
    private static final Caller CENTRAL = new Caller("CENTRAL", null,
            List.of(new GrantSpec(GrantType.CENTRAL, null, null, null)), true,
            List.of("TOP-00000", "ROOT-00001", "KID-00002", "OTHER-00001", "SIB-00003"));
    /** Enforcement OFF: the deployed default. Same grants as DEPT, which must not matter. */
    private static final Caller OFF = new Caller("OFF", null,
            List.of(new GrantSpec(GrantType.UNIT, "7777", null, null)), false,
            List.of("TOP-00000", "ROOT-00001", "KID-00002", "OTHER-00001", "SIB-00003"));

    private static final List<Caller> SCOPED = List.of(DEPT, PI, IO, PARENT);
    private static final List<Caller> UNRESTRICTED = List.of(CENTRAL, OFF);

    @BeforeAll
    static void applyMigrationsAndSeed() throws Exception {
        Path migrationsDir = locateMigrationsDirectory();
        List<Path> migrations;
        try (Stream<Path> files = Files.list(migrationsDir)) {
            migrations = files
                    .filter(path -> MIGRATION_VERSION.matcher(path.getFileName().toString()).matches())
                    .sorted(Comparator.comparingInt(TimeAndMoneySapNegotiationScopingIntegrationTest::migrationVersion))
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
                        is_primary_current, workflow_document_number, account_number
                    ) VALUES
                    (1000, 'TOP-00000',   1, 'Top award',          'Active', '0100', 'NSF', '1234', 'PHYSICS',   TRUE, 'DOC-TOP-1',   'ACC-TOP'),
                    (1001, 'ROOT-00001',  1, 'Root award',         'Active', '0200', 'NIH', '7777', 'CHEMISTRY', TRUE, 'DOC-ROOT-1',  'ACC-ROOT'),
                    (2001, 'KID-00002',   1, 'Kid award title',    'Active', '0100', 'NSF', '1234', 'PHYSICS',   TRUE, 'DOC-KID-1',   'ACC-KID'),
                    (3001, 'OTHER-00001', 1, 'Other secret award', 'Active', '0100', 'NSF', '1234', 'PHYSICS',   TRUE, 'DOC-OTHER-1', 'ACC-OTHER'),
                    (4001, 'SIB-00003',   1, 'Sibling award',      'Active', '0200', 'NIH', '7777', 'CHEMISTRY', TRUE, 'DOC-SIB-1',   'ACC-SIB')
                    """);
            statement.execute("""
                    INSERT INTO archive.award_person (
                        award_person_id, award_id, award_number, sequence_number,
                        full_name, contact_role_code, person_id
                    ) VALUES
                    (101, 1001, 'ROOT-00001', 1, 'P INVESTIGATOR', 'PI', 'P-PI'),
                    (102, 4001, 'SIB-00003',  1, 'P INVESTIGATOR', 'PI', 'P-PI')
                    """);
            statement.execute("""
                    INSERT INTO archive.award_hierarchy (
                        award_hierarchy_id, root_award_number, award_number,
                        parent_award_number, originating_award_number, active
                    ) VALUES
                    (1, 'TOP-00000', 'TOP-00000',  'TOP-00000',  'TOP-00000', 'Y'),
                    (2, 'TOP-00000', 'ROOT-00001', 'TOP-00000',  'TOP-00000', 'Y'),
                    (3, 'TOP-00000', 'KID-00002',  'TOP-00000',  'TOP-00000', 'Y'),
                    (4, 'TOP-00000', 'SIB-00003',  'ROOT-00001', 'TOP-00000', 'Y')
                    """);

            // --- Time and Money -------------------------------------------
            statement.execute("""
                    INSERT INTO archive.award_amount_info (
                        award_amount_info_id, award_id, award_number, sequence_number,
                        transaction_id, tnm_document_number
                    ) VALUES
                    (11, 1001, 'ROOT-00001',  1, 900, 'TM-ROOT'),
                    (12, 2001, 'KID-00002',   1, 900, 'TM-ROOT'),
                    (13, 3001, 'OTHER-00001', 1, 999, 'TM-OTHER')
                    """);
            statement.execute("""
                    INSERT INTO archive.time_and_money_document (
                        document_number, root_award_number, document_status
                    ) VALUES
                    ('TM-ROOT',  'ROOT-00001',  'FINAL'),
                    ('TM-OTHER', 'OTHER-00001', 'FINAL')
                    """);
            statement.execute("""
                    INSERT INTO archive.award_amount_transaction (
                        award_amount_transaction_id, award_number, document_number
                    ) VALUES
                    (21, 'ROOT-00001',  'TM-ROOT'),
                    (22, 'OTHER-00001', 'TM-OTHER')
                    """);
            statement.execute("""
                    INSERT INTO archive.pending_transaction (
                        transaction_id, document_number, source_award_number,
                        destination_award_number, obligated_amount, comments, processed_flag
                    ) VALUES
                    (900, 'TM-ROOT',  'ROOT-00001',  'KID-00002',   1000.00, 'move to kid',              'Y'),
                    (999, 'TM-OTHER', 'OTHER-00001', 'OTHER-00001', 7777.00, 'confidential other money', 'Y')
                    """);
            statement.execute("""
                    INSERT INTO archive.transaction_detail (
                        transaction_detail_id, award_number, sequence_number, transaction_id,
                        time_and_money_document_number, source_award_number,
                        destination_award_number, obligated_amount, transaction_detail_type
                    ) VALUES
                    (7001, 'ROOT-00001',  1, 900, 'TM-ROOT',  'ROOT-00001',  'SIB-00003',   500.00, 'PRIMARY'),
                    (7002, 'ROOT-00001',  1, 900, 'TM-ROOT',  'ROOT-00001',  'KID-00002',   500.00, 'INTERMEDIATE'),
                    (7003, 'OTHER-00001', 1, 999, 'TM-OTHER', 'OTHER-00001', 'OTHER-00001', 7777.00, 'PRIMARY')
                    """);

            // --- SAP transmissions ----------------------------------------
            statement.execute("""
                    INSERT INTO archive.award_transmission (
                        transmission_id, award_id, award_number, sequence_number,
                        success_indicator, sent_data, returned_data, document_number
                    ) VALUES
                    (51, 1001, 'ROOT-00001', 1, 'Y',
                     '<ZGMSPPROGRAMSTRUCTURE>ROOT-00001</ZGMSPPROGRAMSTRUCTURE><ZGMSPPROGRAMSTRUCTURE>KID-00002 Kid award title</ZGMSPPROGRAMSTRUCTURE>',
                     '<RETURN>KID-00002 OK</RETURN>', 'DOC-ROOT-1')
                    """);
            statement.execute("""
                    INSERT INTO archive.award_transmission_child (
                        transmission_child_id, transmission_id, award_id, award_number,
                        sequence_number, child_document_number, lead_unit_number
                    ) VALUES
                    (61, 51, 1001, 'ROOT-00001', 1, 'DOC-ROOT-1', '7777'),
                    (62, 51, 2001, 'KID-00002',  1, 'DOC-KID-1',  '1234'),
                    (63, 51, 4001, 'SIB-00003',  1, 'DOC-SIB-1',  '7777')
                    """);

            // --- Negotiations ---------------------------------------------
            statement.execute("""
                    INSERT INTO archive.proposal_version (
                        proposal_id, proposal_number, version_number, title,
                        proposal_sequence_status, sponsor_code, sponsor_name,
                        lead_unit_number, lead_unit_name, principal_investigator_name
                    ) VALUES
                    (8001, 'P-IN',  1, 'In-unit proposal',    'ACTIVE', '0200', 'NIH', '7777', 'CHEMISTRY', 'A CHEMIST'),
                    (8002, 'P-OUT', 1, 'Secret out proposal', 'ACTIVE', '0100', 'NSF', '1234', 'PHYSICS',   'L PHYSICIST')
                    """);
            statement.execute("""
                    INSERT INTO archive.negotiation (
                        negotiation_id, document_number,
                        negotiation_association_type_code,
                        negotiation_association_type_description,
                        associated_document_id, negotiator_person_id, negotiator_full_name
                    ) VALUES
                    (701, 'NDOC-701', 'AWD', 'Award',                  'KID-00002',  'P-NEG', 'Neg One'),
                    (702, 'NDOC-702', 'AWD', 'Award',                  'ROOT-00001', 'P-NEG', 'Neg Two'),
                    (703, 'NDOC-703', 'NO',  'None',                   'NDOC-703',   'P-NEG', 'Neg Three'),
                    (704, 'NDOC-704', 'IP',  'Institutional Proposal', 'P-OUT',      'P-NEG', 'Neg Four')
                    """);
            statement.execute("""
                    INSERT INTO archive.negotiation_search_attribute (
                        negotiation_id, attribute_source, title, principal_investigator_name,
                        sponsor_code, sponsor_name, lead_unit_number, lead_unit_name
                    ) VALUES
                    (701, 'AWARD',               'Kid award title', 'K SECRETPI',  '0100', 'SECRETSPONSOR', '7777', 'CHEMISTRY'),
                    (702, 'AWARD',               'Root award',      'R VISIBLEPI', '0200', 'NIH',           '7777', 'CHEMISTRY'),
                    (703, 'UNASSOCIATED_DETAIL', 'Plain neg',       'D DETAILPI',  '0200', 'NIH',           '7777', 'CHEMISTRY'),
                    (704, 'NONE',                NULL,              NULL,          NULL,   NULL,            '7777', 'CHEMISTRY')
                    """);
        }

        jdbc = JdbcClient.create(dataSource);
        awardRepository = new AwardArchiveRepository(jdbc);
        negotiationRepository = new NegotiationArchiveRepository(jdbc);
        facts = new JdbcRecordFactsRepository(jdbc, new AwardAccountNumberIoResolver(jdbc));
        unitHierarchy = new JdbcUnitHierarchy(jdbc);
    }

    @AfterEach
    void clearRequest() {
        RequestContextHolder.resetRequestAttributes();
    }

    /** The fixture's premise: each scoped caller opens ROOT-00001 and sees exactly its expected Awards. */
    @Test
    void fixturePremise_eachScopeSeesExactlyItsAwards() {
        for (Caller caller : List.of(DEPT, PI, IO, PARENT, CENTRAL, OFF)) {
            List<String> seen = as(caller, access -> Stream.of(
                            "TOP-00000", "ROOT-00001", "KID-00002", "OTHER-00001", "SIB-00003")
                    .filter(access::canSeeAwardNumber).toList());
            assertThat(seen).as("%s visible Awards", caller).isEqualTo(caller.visibleAwards());
            as(caller, access -> {
                access.requireAward(1001L);
                return null;
            });
        }
    }

    // =====================================================================
    // H1 - Time and Money
    // =====================================================================

    @Test
    void timeAndMoneyTransactionOfAnotherAwardIs404() {
        List<String> failures = new ArrayList<>();
        for (Caller caller : SCOPED) {
            String missing = notFoundMessage(caller,
                    access -> award(access).findTimeAndMoneyTransaction(1001L, 123456L));
            Object outcome = outcome(caller, access -> award(access).findTimeAndMoneyTransaction(1001L, 999L));
            if (!(outcome instanceof NoSuchElementException e)
                    || !e.getMessage().equals(missing.replace("123456", "999"))) {
                failures.add(caller + ": transaction 999 (OTHER-00001) through award 1001 returned " + outcome);
            }
        }
        assertThat(failures).as("expected 404 identical to not-found for every scope").isEmpty();
    }

    @Test
    void timeAndMoneyDocumentOfAnotherAwardIs404() {
        List<String> failures = new ArrayList<>();
        for (Caller caller : SCOPED) {
            String missing = notFoundMessage(caller,
                    access -> award(access).findTimeAndMoneyDocument(1001L, "TM-NOPE"));
            Object outcome = outcome(caller, access -> award(access).findTimeAndMoneyDocument(1001L, "TM-OTHER"));
            if (!(outcome instanceof NoSuchElementException e)
                    || !e.getMessage().equals(missing.replace("TM-NOPE", "TM-OTHER"))) {
                failures.add(caller + ": document TM-OTHER (OTHER-00001) through award 1001 returned " + outcome);
            }
        }
        assertThat(failures).as("expected 404 identical to not-found for every scope").isEmpty();
    }

    @Test
    void ownTransactionStillReturns() {
        for (Caller caller : List.of(DEPT, PI, IO, PARENT, CENTRAL, OFF)) {
            TimeAndMoneyTransactionResponse own =
                    as(caller, access -> award(access).findTimeAndMoneyTransaction(1001L, 900L));
            TimeAndMoneyDocumentResponse document =
                    as(caller, access -> award(access).findTimeAndMoneyDocument(1001L, "TM-ROOT"));

            assertThat(own.pendingTransactionId()).as("%s", caller).isEqualTo(900L);
            assertThat(own.timeAndMoneyDocumentNumber()).as("%s", caller).isEqualTo("TM-ROOT");
            assertThat(own.sourceAwardNumber()).as("%s", caller).isEqualTo("ROOT-00001");
            assertThat(own.comments()).as("%s", caller).isEqualTo("move to kid");
            assertThat(document.rootAwardNumber()).as("%s", caller).isEqualTo("ROOT-00001");
        }
        // A hierarchy node sharing the transaction reaches it through its OWN Award too.
        TimeAndMoneyTransactionResponse viaKid =
                as(CENTRAL, access -> award(access).findTimeAndMoneyTransaction(2001L, 900L));
        assertThat(viaKid.pendingTransactionId()).isEqualTo(900L);
    }

    @Test
    void transactionDetailOmitsUnauthorizedSourceOrDestinationAward() {
        List<String> failures = new ArrayList<>();
        for (Caller caller : SCOPED) {
            TimeAndMoneyTransactionResponse scoped =
                    as(caller, access -> award(access).findTimeAndMoneyTransaction(1001L, 900L));
            // A detail row survives only when every Award it names is visible.
            List<Long> expectedDetails = caller.visibleAwards().contains("SIB-00003") ? List.of(7001L) : List.of();
            List<Long> actualDetails = scoped.details().stream()
                    .map(TimeAndMoneyTransactionDetailResponse::transactionDetailId).toList();
            if (scoped.destinationAwardNumber() != null) {
                failures.add(caller + ": header destination " + scoped.destinationAwardNumber());
            }
            if (!actualDetails.equals(expectedDetails)) {
                failures.add(caller + ": details " + actualDetails + ", expected " + expectedDetails);
            }
            if (String.valueOf(scoped).contains("KID-00002")) {
                failures.add(caller + ": response names KID-00002");
            }
        }
        assertThat(failures).as("hidden Awards named by transaction 900").isEmpty();
    }

    @Test
    void timeAndMoney_centralAndEnforcementOffUnchanged() {
        AwardArchiveService permitAll = new AwardArchiveService(awardRepository, null);
        TimeAndMoneyTransactionResponse unscoped = permitAll.findTimeAndMoneyTransaction(1001L, 900L);
        assertThat(unscoped.destinationAwardNumber()).isEqualTo("KID-00002");
        assertThat(unscoped.details())
                .extracting(TimeAndMoneyTransactionDetailResponse::transactionDetailId)
                .containsExactly(7001L, 7002L);

        for (Caller caller : UNRESTRICTED) {
            TimeAndMoneyTransactionResponse transaction =
                    as(caller, access -> award(access).findTimeAndMoneyTransaction(1001L, 900L));
            TimeAndMoneyDocumentResponse document =
                    as(caller, access -> award(access).findTimeAndMoneyDocument(1001L, "TM-ROOT"));
            assertThat(transaction).as("%s transaction", caller).isEqualTo(unscoped);
            assertThat(document).as("%s document", caller)
                    .isEqualTo(permitAll.findTimeAndMoneyDocument(1001L, "TM-ROOT"));
        }
    }

    // =====================================================================
    // H2 - Negotiation associated record
    // =====================================================================

    @Test
    void negotiationRowsRedactRestrictedAssociatedDocumentId() {
        List<NegotiationSummaryResponse> rows = summaries(DEPT, NegotiationSearchFilters.none());

        assertThat(rows).extracting(NegotiationSummaryResponse::negotiationId)
                .containsExactlyInAnyOrder(701L, 702L, 703L, 704L);

        NegotiationSummaryResponse hiddenAward = row(rows, 701L);
        assertThat(hiddenAward.associatedDocumentId()).as("KID-00002 is not visible to DEPT").isNull();
        assertThat(hiddenAward.title()).as("title resolved FROM the hidden Award").isNull();
        assertThat(hiddenAward.principalInvestigatorName()).isNull();
        assertThat(hiddenAward.sponsorName()).isNull();
        assertThat(hiddenAward.sponsorCode()).isNull();
        assertThat(hiddenAward.leadUnitNumber()).isNull();
        assertThat(row(rows, 704L).associatedDocumentId()).as("P-OUT is not visible to DEPT").isNull();

        // Visible associations and unassociated rows are untouched.
        assertThat(row(rows, 702L).associatedDocumentId()).isEqualTo("ROOT-00001");
        assertThat(row(rows, 702L).title()).isEqualTo("Root award");
        assertThat(row(rows, 703L).associatedDocumentId()).isEqualTo("NDOC-703");
        assertThat(row(rows, 703L).title()).isEqualTo("Plain neg");

        // The workspace row agrees with the list.
        NegotiationRowResponse workspace = as(DEPT, access -> negotiation(access).findWorkspace(701L).current());
        assertThat(workspace.associatedDocumentId()).isNull();
        assertThat(workspace.title()).isNull();
        assertThat(workspace.principalInvestigatorName()).isNull();
        NegotiationRowResponse workspace704 = as(DEPT, access -> negotiation(access).findWorkspace(704L).current());
        assertThat(workspace704.associatedDocumentId()).isNull();

        // Every scope: nothing hidden appears in any listed or workspace row.
        for (Caller caller : SCOPED) {
            List<NegotiationSummaryResponse> listed = summaries(caller, NegotiationSearchFilters.none());
            List<NegotiationRowResponse> workspaces = new ArrayList<>();
            for (NegotiationSummaryResponse r : listed) {
                workspaces.add(as(caller, access -> negotiation(access).findWorkspace(r.negotiationId()).current()));
            }
            assertThat(String.valueOf(listed) + workspaces)
                    .as("%s negotiation rows", caller)
                    .doesNotContain("KID-00002").doesNotContain("Kid award title")
                    .doesNotContain("K SECRETPI").doesNotContain("SECRETSPONSOR").doesNotContain("P-OUT");
        }
    }

    @Test
    void negotiationSearchCannotProbeAnInvisibleAssociation() {
        List<String> failures = new ArrayList<>();
        for (Caller caller : SCOPED) {
            for (NegotiationSearchFilters probe : List.of(
                    NegotiationSearchFilters.ofQuery("KID-00002"),
                    NegotiationSearchFilters.ofQuery("P-OUT"),
                    NegotiationSearchFilters.ofQuery("SECRETSPONSOR"),
                    associationId("KID-00002"),
                    associationId("P-OUT"))) {
                long total = total(caller, probe);
                if (total != 0) {
                    failures.add(caller + ": probe " + probe.query() + "/" + probe.associationId()
                            + " matched " + total + " row(s)");
                }
            }
        }
        assertThat(failures).as("search must not match through a hidden association").isEmpty();

        // A visible association still matches, through both paths.
        assertThat(ids(DEPT, NegotiationSearchFilters.ofQuery("ROOT-00001"))).containsExactly(702L);
        assertThat(ids(DEPT, associationId("ROOT-00001"))).containsExactly(702L);
        assertThat(ids(PI, associationId("ROOT-00001"))).containsExactly(702L);
        assertThat(ids(IO, associationId("ROOT-00001"))).containsExactly(702L);
        // A genuinely unassociated row still matches its own document number.
        assertThat(ids(DEPT, associationId("NDOC-703"))).containsExactly(703L);
        // Central can search the same identifiers.
        assertThat(ids(CENTRAL, NegotiationSearchFilters.ofQuery("KID-00002"))).containsExactly(701L);
        assertThat(ids(CENTRAL, associationId("P-OUT"))).containsExactly(704L);
    }

    @Test
    void negotiationAssociationRedactionAgreesWithPerRecordDecision() {
        for (Caller caller : SCOPED) {
            as(caller, access -> {
                for (NegotiationSummaryResponse listed :
                        negotiation(access).findPage(NegotiationSearchFilters.none(), 0, 25).content()) {
                    NegotiationSummaryResponse raw = row(
                            negotiation(NegotiationRecordAccess.PERMIT_ALL)
                                    .findPage(NegotiationSearchFilters.none(), 0, 25).content(),
                            listed.negotiationId());
                    boolean visible = switch (raw.negotiationAssociationTypeCode()) {
                        case "AWD" -> access.canSeeAwardNumber(raw.associatedDocumentId());
                        case "IP" -> access.canSeeProposalNumber(raw.associatedDocumentId());
                        default -> true;
                    };
                    assertThat(listed.associatedDocumentId())
                            .as("%s negotiation %s: SQL redaction vs canSee", caller, listed.negotiationId())
                            .isEqualTo(visible ? raw.associatedDocumentId() : null);
                }
                return null;
            });
        }
    }

    @Test
    void redactedAssociatedRecordIsIndistinguishableFromNone() {
        NegotiationAssociatedRecordResponse none =
                as(DEPT, access -> negotiation(access).findAssociatedRecord(703L));
        NegotiationAssociatedRecordResponse hiddenAward =
                as(DEPT, access -> negotiation(access).findAssociatedRecord(701L));
        NegotiationAssociatedRecordResponse hiddenProposal =
                as(DEPT, access -> negotiation(access).findAssociatedRecord(704L));

        assertThat(hiddenAward)
                .as("a hidden Award association must answer exactly as no association does")
                .isEqualTo(none);
        assertThat(hiddenProposal)
                .as("a hidden Proposal association must answer exactly as no association does")
                .isEqualTo(none);
        assertThat(String.valueOf(hiddenAward)).doesNotContain("AWD").doesNotContain("Award")
                .doesNotContain("KID-00002");

        NegotiationAssociatedRecordResponse visible =
                as(DEPT, access -> negotiation(access).findAssociatedRecord(702L));
        assertThat(visible.associatedDocumentId()).isEqualTo("ROOT-00001");
        assertThat(visible.clickable()).isTrue();

        // Other scopes reach only 702 (by inheritance) or nothing - never a hidden association.
        for (Caller caller : List.of(PI, IO, PARENT)) {
            for (NegotiationSummaryResponse r : summaries(caller, NegotiationSearchFilters.none())) {
                NegotiationAssociatedRecordResponse associated =
                        as(caller, access -> negotiation(access).findAssociatedRecord(r.negotiationId()));
                assertThat(String.valueOf(associated)).as("%s %s", caller, r.negotiationId())
                        .doesNotContain("KID-00002").doesNotContain("P-OUT");
            }
        }
    }

    @Test
    void negotiation_centralAndEnforcementOffUnchanged() {
        NegotiationArchiveService permitAll = negotiation(NegotiationRecordAccess.PERMIT_ALL);
        PageResponse<NegotiationSummaryResponse> unscoped = permitAll.findPage(NegotiationSearchFilters.none(), 0, 25);
        assertThat(row(unscoped.content(), 701L).associatedDocumentId()).isEqualTo("KID-00002");
        assertThat(permitAll.findAssociatedRecord(701L).associationTypeCode()).isEqualTo("AWD");

        for (Caller caller : UNRESTRICTED) {
            PageResponse<NegotiationSummaryResponse> listed =
                    as(caller, access -> negotiation(access).findPage(NegotiationSearchFilters.none(), 0, 25));
            assertThat(listed).as("%s list", caller).isEqualTo(unscoped);
            PageResponse<NegotiationSummaryResponse> filtered =
                    as(caller, access -> negotiation(access).findPage(associationId("KID-00002"), 0, 25));
            assertThat(filtered).as("%s associationId filter", caller)
                    .isEqualTo(permitAll.findPage(associationId("KID-00002"), 0, 25));
            for (long id : List.of(701L, 702L, 703L, 704L)) {
                NegotiationAssociatedRecordResponse associated =
                        as(caller, access -> negotiation(access).findAssociatedRecord(id));
                NegotiationWorkspaceResponse workspace =
                        as(caller, access -> negotiation(access).findWorkspace(id));
                assertThat(associated).as("%s associated record %s", caller, id)
                        .isEqualTo(permitAll.findAssociatedRecord(id));
                assertThat(workspace).as("%s workspace %s", caller, id)
                        .isEqualTo(permitAll.findWorkspace(id));
            }
        }
    }

    // =====================================================================
    // H3 - SAP transmissions (and the Award Report PDF that reuses them)
    // =====================================================================

    @Test
    void sapTransmissionChildrenOmitUnauthorizedAwards() {
        List<String> failures = new ArrayList<>();
        for (Caller caller : SCOPED) {
            List<AwardSapTransmissionResponse> scoped =
                    as(caller, access -> award(access).findSapTransmissions(1001L, 0, 25).content());
            List<String> children = childAwardNumbers(scoped.get(0));
            if (!children.equals(caller.visibleAwards())) {
                failures.add(caller + ": children " + children + ", expected " + caller.visibleAwards());
            }
        }
        assertThat(failures).as("SAP transmission children the caller cannot open").isEmpty();
    }

    @Test
    void sapRawPayloadNotExposedToScopedCaller() {
        List<String> failures = new ArrayList<>();
        for (Caller caller : SCOPED) {
            AwardSapTransmissionResponse scoped =
                    as(caller, access -> award(access).findSapTransmissions(1001L, 0, 25).content().get(0));
            if (scoped.sentData() != null || scoped.returnedData() != null) {
                failures.add(caller + ": raw SAP payload returned (it embeds KID-00002)");
            }
            // The transmission's own metadata is still there.
            assertThat(scoped.transmissionId()).isEqualTo(51L);
            assertThat(scoped.documentNumber()).isEqualTo("DOC-ROOT-1");
        }
        assertThat(failures).as("raw SAP payloads for scoped callers").isEmpty();
    }

    @Test
    void awardReportPdfInheritsFilteredSap() {
        List<String> failures = new ArrayList<>();
        for (Caller caller : SCOPED) {
            AwardReportData report =
                    as(caller, access -> new AwardReportService(award(access)).buildReportData(1001L));
            AwardSapTransmissionResponse transmission = report.sapTransmissions().get(0);
            if (!childAwardNumbers(transmission).equals(caller.visibleAwards())
                    || transmission.sentData() != null
                    || transmission.returnedData() != null
                    || String.valueOf(report.sapTransmissions()).contains("KID-00002")) {
                failures.add(caller + ": report SAP section " + report.sapTransmissions());
            }
        }
        assertThat(failures).as("Award Report SAP section for scoped callers").isEmpty();
    }

    @Test
    void sap_centralAndEnforcementOffUnchanged() {
        AwardArchiveService permitAll = new AwardArchiveService(awardRepository, null);
        PageResponse<AwardSapTransmissionResponse> unscoped = permitAll.findSapTransmissions(1001L, 0, 25);
        assertThat(unscoped.content().get(0).children()).hasSize(3);
        assertThat(unscoped.content().get(0).sentData()).contains("KID-00002");

        for (Caller caller : UNRESTRICTED) {
            PageResponse<AwardSapTransmissionResponse> page =
                    as(caller, access -> award(access).findSapTransmissions(1001L, 0, 25));
            assertThat(page).as("%s", caller).isEqualTo(unscoped);
            AwardReportData report =
                    as(caller, access -> new AwardReportService(award(access)).buildReportData(1001L));
            assertThat(report.sapTransmissions()).as("%s report", caller).isEqualTo(unscoped.content());
        }
    }

    // --- harness -----------------------------------------------------------

    private static <T> T as(Caller caller, Function<RecordAuthorizationService, T> work) {
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(new MockHttpServletRequest()));
        try {
            return work.apply(authorization(caller));
        } finally {
            RequestContextHolder.resetRequestAttributes();
        }
    }

    /** The value returned, or the RuntimeException thrown. */
    private static Object outcome(Caller caller, Function<RecordAuthorizationService, ?> work) {
        try {
            return as(caller, work);
        } catch (RuntimeException thrown) {
            return thrown;
        }
    }

    private static String notFoundMessage(Caller caller, Function<RecordAuthorizationService, ?> work) {
        Object outcome = outcome(caller, work);
        assertThat(outcome).isInstanceOf(NoSuchElementException.class);
        return ((NoSuchElementException) outcome).getMessage();
    }

    private static RecordAuthorizationService authorization(Caller caller) {
        String institutionalId = "SYN-INST-" + caller.name();
        var identity = new ValidatedCognitoIdentity(ISSUER, "syn-sub-" + caller.name(), "syn-" + caller.name(), NOW);
        var links = new InMemoryIdentityLinkRepository();
        links.link(identity, institutionalId, caller.personId(), "syn-" + caller.name(), IdentityLinkStatus.ACTIVE);
        var grants = new InMemoryAccessGrantRepository();
        long id = 1;
        for (GrantSpec spec : caller.grants()) {
            grants.add(new AccessGrant(id++, new InstitutionalIdentifier(institutionalId), spec.type(),
                    spec.unit(), false, spec.io(), null, null, null, spec.awardNumber()));
        }

        var properties = new AuthorizationProperties();
        properties.setEnforcementEnabled(caller.enforced());
        properties.setVersionScope(AuthorizationPolicy.VersionScope.FAMILY_WIDE);
        properties.setDepartmentMatch(AuthorizationPolicy.DepartmentMatch.EXACT_LEAD_UNIT);
        properties.setResearchStaffRoles(Set.of("PI", "MPI", "COI"));
        properties.setContactDerivation(AuthorizationPolicy.ContactDerivation.EXPLICIT_GRANT);
        properties.setMaxSignInAge(Duration.ofHours(8));

        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        // Wired as AuthorizationConfiguration wires production: real IO SQL and real hierarchy.
        return new RecordAuthorizationService(
                properties,
                () -> Optional.of(identity),
                new IdentityResolver(links),
                new AccessScopeResolver(grants, clock, properties::policy, ContactRelationships.NONE,
                        new JdbcAwardHierarchy(jdbc)),
                facts,
                unitHierarchy,
                unitHierarchy,
                new AwardAccountNumberIoSql(),
                IdentityEnrollment.DISABLED,
                clock);
    }

    private static AwardArchiveService award(RecordAuthorizationService access) {
        return new AwardArchiveService(awardRepository, null, access);
    }

    private static NegotiationArchiveService negotiation(NegotiationRecordAccess access) {
        return new NegotiationArchiveService(negotiationRepository, null, access);
    }

    private static NegotiationSearchFilters associationId(String value) {
        return new NegotiationSearchFilters(
                null, null, null, null, null, null, null, null, value, null, null, null, null);
    }

    private static List<NegotiationSummaryResponse> summaries(Caller caller, NegotiationSearchFilters filters) {
        return as(caller, access -> negotiation(access).findPage(filters, 0, 25).content());
    }

    private static long total(Caller caller, NegotiationSearchFilters filters) {
        return as(caller, access -> negotiation(access).findPage(filters, 0, 25).totalElements());
    }

    private static List<Long> ids(Caller caller, NegotiationSearchFilters filters) {
        return summaries(caller, filters).stream().map(NegotiationSummaryResponse::negotiationId).toList();
    }

    private static NegotiationSummaryResponse row(List<NegotiationSummaryResponse> rows, long id) {
        return rows.stream().filter(r -> r.negotiationId() == id).findFirst().orElseThrow();
    }

    private static List<String> childAwardNumbers(AwardSapTransmissionResponse transmission) {
        return transmission.children().stream().map(AwardSapTransmissionChildResponse::awardNumber).toList();
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
