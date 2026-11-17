package edu.bu.archive.adapter.out.persistence.authorization;

import edu.bu.archive.adapter.in.web.dto.award.AwardAssociatedNegotiationResponse;
import edu.bu.archive.adapter.in.web.dto.award.AwardFundingProposalResponse;
import edu.bu.archive.adapter.in.web.dto.award.AwardFundingSubawardResponse;
import edu.bu.archive.adapter.in.web.dto.award.AwardProposalResponse;
import edu.bu.archive.adapter.in.web.dto.proposal.ProposalAwardResponse;
import edu.bu.archive.adapter.in.web.dto.proposal.ProposalFundedAwardResponse;
import edu.bu.archive.adapter.out.persistence.AwardArchiveRepository;
import edu.bu.archive.adapter.out.persistence.NegotiationArchiveRepository;
import edu.bu.archive.adapter.out.persistence.ProposalArchiveRepository;
import edu.bu.archive.adapter.out.persistence.ProposalV1Repository;
import edu.bu.archive.adapter.out.persistence.SubawardArchiveRepository;
import edu.bu.archive.application.authorization.AccessGrant;
import edu.bu.archive.application.authorization.AccessScopeResolver;
import edu.bu.archive.application.authorization.AuthorizationPolicy;
import edu.bu.archive.application.authorization.AuthorizationProperties;
import edu.bu.archive.application.authorization.AwardHierarchy;
import edu.bu.archive.application.authorization.ContactRelationships;
import edu.bu.archive.application.authorization.GrantType;
import edu.bu.archive.application.authorization.IdentityEnrollment;
import edu.bu.archive.application.authorization.IdentityLinkStatus;
import edu.bu.archive.application.authorization.IdentityResolver;
import edu.bu.archive.application.authorization.InstitutionalIdentifier;
import edu.bu.archive.application.authorization.IoSqlStrategy;
import edu.bu.archive.application.authorization.ProposalRecordAccess;
import edu.bu.archive.application.authorization.RecordAuthorizationService;
import edu.bu.archive.application.authorization.ValidatedCognitoIdentity;
import edu.bu.archive.application.authorization.fixtures.InMemoryAccessGrantRepository;
import edu.bu.archive.application.authorization.fixtures.InMemoryIdentityLinkRepository;
import edu.bu.archive.application.award.AwardArchiveService;
import edu.bu.archive.application.negotiation.NegotiationArchiveService;
import edu.bu.archive.application.proposal.ProposalArchiveService;
import edu.bu.archive.application.proposal.ProposalArchiveV1Service;
import edu.bu.archive.application.subaward.SubawardArchiveService;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
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
import java.util.Comparator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Defect B1 (2026-11-17): related-record lists must apply the RELATED
 * module's authorization rule, not only the parent's.
 *
 * <p>Before the fix, with enforcement ON, each endpoint below checked only
 * the parent record and then returned every related row - disclosing the
 * title, PI, sponsor, amounts and negotiator of related records the caller
 * could not open:
 * <ul>
 *   <li>GET /api/v1/awards/{awardId}/funding-proposals</li>
 *   <li>GET /api/v1/awards/{awardId}/funding-subawards</li>
 *   <li>GET /api/v1/awards/{awardId}/negotiations</li>
 *   <li>GET /api/v1/proposals/{id}/funded-awards</li>
 *   <li>GET /api/proposals/{n}/awards</li>
 *   <li>GET /api/awards/{n}/proposals and the counts in /api/awards/{n}/funding</li>
 * </ul>
 * The Award Report PDF reuses the first three service methods and is fixed
 * by the same change.
 *
 * <p>Everything here runs through a REAL {@link RecordAuthorizationService}
 * with enforcement ON, real facts from PostgreSQL and real grants - no fake
 * port - so the agreement tests compare the actual SQL predicate with the
 * actual per-record decision. None of these tests touches a method added by
 * the fix, so the class compiles and runs unchanged against the unfixed
 * production code, which is how the defect was proven.
 *
 * <p>Fixtures are synthetic. No BU data appears here.
 *
 * <pre>
 * Units: 7000 > 7777 (CHEMISTRY); 1234 (PHYSICS)
 *
 * Awards (award_id number seq lead current)
 *   1001 HUB-00001 1 7777 no    FARRER is PI on THIS version only
 *   1002 HUB-00001 2 7777 yes
 *   2001 OUT-00001 1 1234 yes
 *   3001 IN-00001  1 7777 yes   FARRER is PI
 *   4001 PAR-00001 1 1234 yes
 *
 * Proposals: 8001 P-IN (7777), 8002 P-OUT (1234, FARRER is PI)
 * HUB funded by P-IN and P-OUT  (award_funding_proposal on 1002)
 * P-IN funds HUB, IN, OUT; P-OUT funds IN, OUT  (proposal_award)
 *
 * Subawards (funding award_ids)
 *   9101 SUB-A  PAR 4001 + IN 3001
 *   9102 SUB-B  PAR 4001 only
 *   9103 SUB-C  HUB 1001 (the superseded version)
 *   9104 SUB-D  HUB 1002
 *
 * Negotiations ('Award'-typed)
 *   501 on PAR-00001, lead unit 7777
 *   502 on PAR-00001, lead unit 1234
 *   503 on HUB-00001, lead unit 1234
 * </pre>
 *
 * Callers: DEPT (UNIT 7777); MIXED (UNIT 7777 + PARENT_AWARD PAR-00001,
 * which per SEC-005 reaches the Award only and is never inherited);
 * FARRER (contact derivation); CENTRAL; and enforcement OFF.
 */
@Tag("database")
@Testcontainers(disabledWithoutDocker = true)
class RelatedRecordScopingIntegrationTest {

    private static final Pattern MIGRATION_VERSION = Pattern.compile("^V(\\d+)__.*\\.sql$");
    private static final Instant NOW = Instant.parse("2026-11-17T12:00:00Z");
    private static final String ISSUER = "https://idp.test.invalid/synthetic";

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg17").asCompatibleSubstituteFor("postgres"));

    private static JdbcClient jdbc;
    private static AwardArchiveRepository awardRepository;
    private static ProposalV1Repository proposalV1Repository;
    private static ProposalArchiveRepository proposalRepository;
    private static SubawardArchiveRepository subawardRepository;
    private static NegotiationArchiveRepository negotiationRepository;
    private static JdbcRecordFactsRepository facts;
    private static JdbcUnitHierarchy unitHierarchy;

    /** A synthetic caller: its grants, and the Kuali person it is linked to (if any). */
    private record Caller(String name, String personId, List<GrantSpec> grants, boolean enforced) {
        @Override
        public String toString() {
            return name;
        }
    }

    private record GrantSpec(GrantType type, String unit, String awardNumber) { }

    private static final Caller DEPT = new Caller("DEPT", null,
            List.of(new GrantSpec(GrantType.UNIT, "7777", null)), true);
    private static final Caller MIXED = new Caller("MIXED", null,
            List.of(new GrantSpec(GrantType.UNIT, "7777", null),
                    new GrantSpec(GrantType.PARENT_AWARD, null, "PAR-00001")), true);
    private static final Caller FARRER = new Caller("FARRER", "P-FARRER",
            List.of(new GrantSpec(GrantType.CONTACT_DERIVATION, null, null)), true);
    private static final Caller CENTRAL = new Caller("CENTRAL", null,
            List.of(new GrantSpec(GrantType.CENTRAL, null, null)), true);
    /** Enforcement OFF: the deployed default. Same grants as DEPT, which must not matter. */
    private static final Caller OFF = new Caller("OFF", null,
            List.of(new GrantSpec(GrantType.UNIT, "7777", null)), false);

    @BeforeAll
    static void applyMigrationsAndSeed() throws Exception {
        Path migrationsDir = locateMigrationsDirectory();
        List<Path> migrations;
        try (Stream<Path> files = Files.list(migrationsDir)) {
            migrations = files
                    .filter(path -> MIGRATION_VERSION.matcher(path.getFileName().toString()).matches())
                    .sorted(Comparator.comparingInt(RelatedRecordScopingIntegrationTest::migrationVersion))
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
                    ('7000', 'SCIENCES',  NULL),
                    ('7777', 'CHEMISTRY', '7000'),
                    ('1234', 'PHYSICS',   NULL)
                    """);
            statement.execute("""
                    INSERT INTO archive.award_version (
                        award_id, award_number, sequence_number, title, status_description,
                        sponsor_code, sponsor_name, lead_unit_number, lead_unit_name,
                        is_primary_current, workflow_document_number
                    ) VALUES
                    (1001, 'HUB-00001', 1, 'Hub award v1',     'Closed', '0200', 'NIH', '7777', 'CHEMISTRY', FALSE, 'DOC-HUB-1'),
                    (1002, 'HUB-00001', 2, 'Hub award v2',     'Active', '0200', 'NIH', '7777', 'CHEMISTRY', TRUE,  'DOC-HUB-2'),
                    (2001, 'OUT-00001', 1, 'Secret out award', 'Active', '0100', 'NSF', '1234', 'PHYSICS',   TRUE,  'DOC-OUT-1'),
                    (3001, 'IN-00001',  1, 'In-unit award',    'Active', '0200', 'NIH', '7777', 'CHEMISTRY', TRUE,  'DOC-IN-1'),
                    (4001, 'PAR-00001', 1, 'Parent award',     'Active', '0100', 'NSF', '1234', 'PHYSICS',   TRUE,  'DOC-PAR-1')
                    """);
            statement.execute("""
                    INSERT INTO archive.award_person (
                        award_person_id, award_id, award_number, sequence_number,
                        full_name, contact_role_code, person_id
                    ) VALUES
                    (101, 1001, 'HUB-00001', 1, 'L FARRER', 'PI', 'P-FARRER'),
                    (102, 3001, 'IN-00001',  1, 'L FARRER', 'PI', 'P-FARRER')
                    """);
            statement.execute("""
                    INSERT INTO archive.proposal_version (
                        proposal_id, proposal_number, version_number, title,
                        proposal_sequence_status, sponsor_code, sponsor_name,
                        lead_unit_number, lead_unit_name, principal_investigator_name
                    ) VALUES
                    (8001, 'P-IN',  1, 'In-unit proposal',    'ACTIVE', '0200', 'NIH', '7777', 'CHEMISTRY', 'A CHEMIST'),
                    (8002, 'P-OUT', 1, 'Secret out proposal', 'ACTIVE', '0100', 'NSF', '1234', 'PHYSICS',   'L FARRER')
                    """);
            statement.execute("""
                    INSERT INTO archive.proposal_person (
                        proposal_person_id, proposal_id, proposal_number,
                        sequence_number, person_id, full_name, contact_role_code
                    ) VALUES
                    (201, 8002, 'P-OUT', 1, 'P-FARRER', 'L FARRER', 'PI')
                    """);
            statement.execute("""
                    INSERT INTO archive.award_funding_proposal (
                        award_funding_proposal_id, award_id, proposal_id, active_flag
                    ) VALUES
                    (601, 1002, 8001, 'Y'),
                    (602, 1002, 8002, 'Y')
                    """);
            statement.execute("""
                    INSERT INTO archive.proposal_award (
                        proposal_id, award_id, award_number, award_funding_proposal_id, active
                    ) VALUES
                    (8001, 1002, 'HUB-00001', 701, TRUE),
                    (8001, 3001, 'IN-00001',  702, TRUE),
                    (8001, 2001, 'OUT-00001', 703, TRUE),
                    (8002, 3001, 'IN-00001',  704, TRUE),
                    (8002, 2001, 'OUT-00001', 705, TRUE)
                    """);
            statement.execute("""
                    INSERT INTO archive.subaward (
                        subaward_id, subaward_code, sequence_number, document_number,
                        title, status_description, subaward_sequence_status,
                        organization_id, account_number, requisitioner_unit
                    ) VALUES
                    (9101, 'SUB-A', 1, 'SDOC-9101', 'Sub A', 'Active', 'ACTIVE', 'ORG-A', 'ACC-A', '7777'),
                    (9102, 'SUB-B', 1, 'SDOC-9102', 'Sub B', 'Active', 'ACTIVE', 'ORG-B', 'ACC-B', '1234'),
                    (9103, 'SUB-C', 1, 'SDOC-9103', 'Sub C', 'Active', 'ACTIVE', 'ORG-C', 'ACC-C', '7777'),
                    (9104, 'SUB-D', 1, 'SDOC-9104', 'Sub D', 'Active', 'ACTIVE', 'ORG-D', 'ACC-D', '7777')
                    """);
            statement.execute("""
                    INSERT INTO archive.subaward_funding (
                        subaward_funding_source_id, subaward_id, subaward_code,
                        sequence_number, award_id, award_number
                    ) VALUES
                    (301, 9101, 'SUB-A', 1, 4001, 'PAR-00001'),
                    (302, 9101, 'SUB-A', 1, 3001, 'IN-00001'),
                    (303, 9102, 'SUB-B', 1, 4001, 'PAR-00001'),
                    (304, 9103, 'SUB-C', 1, 1001, 'HUB-00001'),
                    (305, 9104, 'SUB-D', 1, 1002, 'HUB-00001')
                    """);
            statement.execute("""
                    INSERT INTO archive.negotiation (
                        negotiation_id, document_number,
                        negotiation_association_type_code,
                        negotiation_association_type_description,
                        associated_document_id, negotiator_person_id, negotiator_full_name
                    ) VALUES
                    (501, 'NDOC-501', 'AWD', 'Award', 'PAR-00001', 'P-NEG', 'Visible Negotiator'),
                    (502, 'NDOC-502', 'AWD', 'Award', 'PAR-00001', 'P-NEG', 'Secret Negotiator'),
                    (503, 'NDOC-503', 'AWD', 'Award', 'HUB-00001', 'P-NEG', 'Hub Negotiator')
                    """);
            statement.execute("""
                    INSERT INTO archive.negotiation_search_attribute (
                        negotiation_id, attribute_source, title, lead_unit_number, lead_unit_name
                    ) VALUES
                    (501, 'AWARD', 'Par neg in unit',  '7777', 'CHEMISTRY'),
                    (502, 'AWARD', 'Par neg out unit', '1234', 'PHYSICS'),
                    (503, 'AWARD', 'Hub neg out unit', '1234', 'PHYSICS')
                    """);
        }

        jdbc = JdbcClient.create(dataSource);
        awardRepository = new AwardArchiveRepository(jdbc);
        proposalV1Repository = new ProposalV1Repository(jdbc);
        proposalRepository = new ProposalArchiveRepository(jdbc);
        subawardRepository = new SubawardArchiveRepository(jdbc);
        negotiationRepository = new NegotiationArchiveRepository(jdbc);
        facts = new JdbcRecordFactsRepository(jdbc, (module, versionKey) -> Set.of());
        unitHierarchy = new JdbcUnitHierarchy(jdbc);
    }

    @AfterEach
    void clearRequest() {
        RequestContextHolder.resetRequestAttributes();
    }

    // =====================================================================
    // GET /api/v1/awards/{awardId}/funding-proposals
    // =====================================================================

    @Test
    void fundingProposals_departmentCallerSeesOnlyInUnitProposals() {
        List<String> numbers = as(DEPT, access -> proposalNumbers(award(access).findFundingProposals(1002L)));

        assertThat(numbers)
                .as("DEPT (unit 7777) may open HUB-00001 but not P-OUT (unit 1234); "
                        + "expected funding-proposals to omit P-OUT")
                .containsExactly("P-IN");
    }

    @Test
    void fundingProposals_outOfUnitProposalIsOmittedEntirelyWhileTheParentIsVisible() {
        String rendered = as(DEPT, access -> {
            AwardArchiveService service = award(access);
            service.findFamily("HUB-00001"); // the parent is visible
            return String.valueOf(service.findFundingProposals(1002L));
        });

        // Not as a row, not as a stub: no number, title, PI, sponsor or id.
        assertThat(rendered)
                .doesNotContain("P-OUT")
                .doesNotContain("Secret out proposal")
                .doesNotContain("L FARRER")
                .doesNotContain("NSF")
                .doesNotContain("8002");
    }

    @Test
    void fundingProposals_contactCallerSeesOnlyProposalsTheyAreListedOn() {
        // FARRER reaches HUB-00001 through v1 (1001), where they are PI.
        List<String> numbers = as(FARRER, access -> proposalNumbers(award(access).findFundingProposals(1001L)));

        assertThat(numbers)
                .as("FARRER is PI on P-OUT only; expected funding-proposals to omit P-IN")
                .containsExactly("P-OUT");
    }

    @Test
    void fundingProposals_centralSeesEveryRelation() {
        assertThat(asList(CENTRAL, access -> proposalNumbers(award(access).findFundingProposals(1002L))))
                .containsExactlyInAnyOrder("P-IN", "P-OUT");
    }

    @Test
    void fundingProposals_enforcementOffIsUnchanged() {
        List<AwardFundingProposalResponse> off = as(OFF, access -> award(access).findFundingProposals(1002L));
        List<AwardFundingProposalResponse> permitAll =
                new AwardArchiveService(awardRepository, null).findFundingProposals(1002L);

        assertThat(off).isEqualTo(permitAll).isEqualTo(awardRepository.findFundingProposalRows("HUB-00001"));
        assertThat(proposalNumbers(off)).containsExactlyInAnyOrder("P-IN", "P-OUT");
    }

    @Test
    void fundingProposals_sqlAndPerRecordDecisionAgree() {
        for (Caller caller : List.of(DEPT, MIXED, FARRER, CENTRAL)) {
            long parent = caller == FARRER ? 1001L : 1002L;
            List<AwardFundingProposalResponse> all =
                    new AwardArchiveService(awardRepository, null).findFundingProposals(parent);
            as(caller, access -> {
                assertThat(award(access).findFundingProposals(parent))
                        .as("%s: listed funding proposals vs canSeeProposal(navigableActiveProposalId)", caller)
                        .containsExactlyInAnyOrderElementsOf(keep(all, row ->
                                row.navigableActiveProposalId() != null
                                        && access.canSeeProposal(row.navigableActiveProposalId())));
                return null;
            });
        }
    }

    // =====================================================================
    // GET /api/v1/awards/{awardId}/funding-subawards
    // =====================================================================

    @Test
    void fundingSubawards_departmentCallerSeesOnlyInheritedSubawards() {
        // MIXED opens PAR-00001 through its PARENT_AWARD grant. SEC-005: that
        // grant is never inherited, so only SUB-A (also funded by the in-unit
        // IN-00001) is visible; SUB-B's only funder is PAR-00001.
        List<String> codes = as(MIXED, access -> {
            award(access).findFamily("PAR-00001");
            return subawardCodes(award(access).findFundingSubawards(4001L));
        });

        assertThat(codes)
                .as("expected funding-subawards to omit SUB-B")
                .containsExactly("SUB-A");
    }

    @Test
    void fundingSubawards_omittedSubawardLeavesNoTrace() {
        String rendered = as(MIXED, access -> String.valueOf(award(access).findFundingSubawards(4001L)));

        assertThat(rendered).doesNotContain("SUB-B").doesNotContain("ORG-B").doesNotContain("9102");
    }

    @Test
    void fundingSubawards_departmentInheritanceThroughTheParentIsUnchanged() {
        // DEPT can see HUB-00001 itself, so every Subaward it funds is inherited.
        assertThat(asList(DEPT, access -> subawardCodes(award(access).findFundingSubawards(1002L))))
                .containsExactly("SUB-C", "SUB-D");
    }

    @Test
    void fundingSubawards_contactCallerSeesOnlyTheVersionTheyAreListedOn() {
        // FARRER is PI on HUB v1 only (SEC-003); SUB-D is funded by v2.
        assertThat(asList(FARRER, access -> subawardCodes(award(access).findFundingSubawards(1001L))))
                .as("expected funding-subawards to omit SUB-D")
                .containsExactly("SUB-C");
    }

    @Test
    void fundingSubawards_centralSeesEveryRelation() {
        assertThat(asList(CENTRAL, access -> subawardCodes(award(access).findFundingSubawards(4001L))))
                .containsExactly("SUB-A", "SUB-B");
    }

    @Test
    void fundingSubawards_enforcementOffIsUnchanged() {
        List<AwardFundingSubawardResponse> off = as(OFF, access -> award(access).findFundingSubawards(4001L));

        assertThat(off)
                .isEqualTo(new AwardArchiveService(awardRepository, null).findFundingSubawards(4001L))
                .isEqualTo(awardRepository.findFundingSubawardRows("PAR-00001"));
        assertThat(subawardCodes(off)).containsExactly("SUB-A", "SUB-B");
    }

    @Test
    void fundingSubawards_sqlAndPerRecordDecisionAgree() {
        record Case(Caller caller, long awardId) { }
        for (Case c : List.of(new Case(MIXED, 4001L), new Case(DEPT, 1002L),
                new Case(FARRER, 1001L), new Case(CENTRAL, 4001L), new Case(CENTRAL, 1002L))) {
            List<AwardFundingSubawardResponse> all =
                    new AwardArchiveService(awardRepository, null).findFundingSubawards(c.awardId());
            as(c.caller(), access -> {
                assertThat(award(access).findFundingSubawards(c.awardId()))
                        .as("%s on %s: listed subawards vs canSeeSubaward", c.caller(), c.awardId())
                        .containsExactlyElementsOf(keep(all, row ->
                                access.canSeeSubaward(row.navigableCurrentSubawardId())));
                return null;
            });
        }
    }

    // =====================================================================
    // GET /api/v1/awards/{awardId}/negotiations
    // =====================================================================

    @Test
    void negotiations_departmentCallerSeesOnlyInScopeNegotiations() {
        List<Long> ids = as(MIXED, access -> negotiationIds(award(access).findAssociatedNegotiations(4001L)));

        assertThat(ids)
                .as("MIXED holds unit 7777 and a PARENT_AWARD grant on PAR-00001; "
                        + "expected negotiations to omit 502 (unit 1234)")
                .containsExactly(501L);
    }

    @Test
    void negotiations_omittedNegotiationLeavesNoTrace() {
        String rendered = as(MIXED, access -> String.valueOf(award(access).findAssociatedNegotiations(4001L)));

        assertThat(rendered).doesNotContain("NDOC-502").doesNotContain("Secret Negotiator");
    }

    @Test
    void negotiations_contactOnASupersededVersionInheritsNoNegotiation() {
        // FARRER opens HUB v1; the Award-path pins the CURRENT version.
        assertThat(asList(FARRER, access -> negotiationIds(award(access).findAssociatedNegotiations(1001L))))
                .as("expected negotiations to omit 503")
                .isEmpty();
    }

    @Test
    void negotiations_departmentInheritanceThroughTheParentIsUnchanged() {
        assertThat(asList(DEPT, access -> negotiationIds(award(access).findAssociatedNegotiations(1002L))))
                .containsExactly(503L);
    }

    @Test
    void negotiations_centralSeesEveryRelation() {
        assertThat(asList(CENTRAL, access -> negotiationIds(award(access).findAssociatedNegotiations(4001L))))
                .containsExactlyInAnyOrder(501L, 502L);
    }

    @Test
    void negotiations_enforcementOffIsUnchanged() {
        List<AwardAssociatedNegotiationResponse> off =
                as(OFF, access -> award(access).findAssociatedNegotiations(4001L));

        assertThat(off)
                .isEqualTo(new AwardArchiveService(awardRepository, null).findAssociatedNegotiations(4001L))
                .isEqualTo(awardRepository.findAssociatedNegotiationRows("PAR-00001"));
        assertThat(negotiationIds(off)).containsExactlyInAnyOrder(501L, 502L);
    }

    @Test
    void negotiations_sqlAndPerRecordDecisionAgree() {
        record Case(Caller caller, long awardId) { }
        for (Case c : List.of(new Case(MIXED, 4001L), new Case(DEPT, 1002L),
                new Case(FARRER, 1001L), new Case(CENTRAL, 4001L))) {
            List<AwardAssociatedNegotiationResponse> all =
                    new AwardArchiveService(awardRepository, null).findAssociatedNegotiations(c.awardId());
            as(c.caller(), access -> {
                assertThat(award(access).findAssociatedNegotiations(c.awardId()))
                        .as("%s on %s: listed negotiations vs canSeeNegotiation", c.caller(), c.awardId())
                        .containsExactlyElementsOf(keep(all, row -> access.canSeeNegotiation(row.negotiationId())));
                return null;
            });
        }
    }

    // =====================================================================
    // GET /api/v1/proposals/{id}/funded-awards
    // =====================================================================

    @Test
    void fundedAwards_departmentCallerSeesOnlyInUnitAwards() {
        assertThat(asList(DEPT, access -> fundedAwardNumbers(proposalV1(access).findFundedAwards(8001L))))
                .as("expected funded-awards to omit OUT-00001")
                .containsExactly("HUB-00001", "IN-00001");
    }

    @Test
    void fundedAwards_omittedAwardLeavesNoTrace() {
        String rendered = as(DEPT, access -> {
            proposalV1(access).findSummary(8001L); // the parent is visible
            return String.valueOf(proposalV1(access).findFundedAwards(8001L));
        });

        assertThat(rendered).doesNotContain("OUT-00001").doesNotContain("Secret out award")
                .doesNotContain("2001");
    }

    @Test
    void fundedAwards_contactCallerSeesOnlyAwardsTheyAreListedOn() {
        assertThat(asList(FARRER, access -> fundedAwardNumbers(proposalV1(access).findFundedAwards(8002L))))
                .as("FARRER is PI on IN-00001 only; expected funded-awards to omit OUT-00001")
                .containsExactly("IN-00001");
    }

    @Test
    void fundedAwards_centralSeesEveryRelation() {
        assertThat(asList(CENTRAL, access -> fundedAwardNumbers(proposalV1(access).findFundedAwards(8001L))))
                .containsExactly("HUB-00001", "IN-00001", "OUT-00001");
    }

    @Test
    void fundedAwards_enforcementOffIsUnchanged() {
        List<ProposalFundedAwardResponse> off = as(OFF, access -> proposalV1(access).findFundedAwards(8001L));

        assertThat(off)
                .isEqualTo(proposalV1(ProposalRecordAccess.PERMIT_ALL).findFundedAwards(8001L))
                .isEqualTo(proposalV1Repository.findFundedAwardRows("P-IN"));
        assertThat(fundedAwardNumbers(off)).containsExactly("HUB-00001", "IN-00001", "OUT-00001");
    }

    @Test
    void fundedAwards_sqlAndPerRecordDecisionAgree() {
        record Case(Caller caller, long proposalId) { }
        for (Case c : List.of(new Case(DEPT, 8001L), new Case(MIXED, 8001L),
                new Case(FARRER, 8002L), new Case(CENTRAL, 8001L))) {
            List<ProposalFundedAwardResponse> all =
                    proposalV1(ProposalRecordAccess.PERMIT_ALL).findFundedAwards(c.proposalId());
            as(c.caller(), access -> {
                assertThat(proposalV1(access).findFundedAwards(c.proposalId()))
                        .as("%s on %s: listed awards vs canSeeAwardNumber", c.caller(), c.proposalId())
                        .containsExactlyElementsOf(keep(all, row -> access.canSeeAwardNumber(row.awardNumber())));
                return null;
            });
        }
    }

    // =====================================================================
    // GET /api/proposals/{n}/awards (legacy)
    // =====================================================================

    @Test
    void legacyProposalAwards_departmentCallerSeesOnlyInUnitAwards() {
        assertThat(asList(DEPT, access -> legacyAwardNumbers(proposalLegacy(access).findAwards("P-IN"))))
                .as("expected /api/proposals/P-IN/awards to omit OUT-00001")
                .containsExactly("HUB-00001", "IN-00001");
    }

    @Test
    void legacyProposalAwards_contactCallerSeesOnlyAwardsTheyAreListedOn() {
        assertThat(asList(FARRER, access -> legacyAwardNumbers(proposalLegacy(access).findAwards("P-OUT"))))
                .as("expected /api/proposals/P-OUT/awards to omit OUT-00001")
                .containsExactly("IN-00001");
    }

    @Test
    void legacyProposalAwards_centralAndEnforcementOffSeeEveryRelation() {
        List<ProposalAwardResponse> unscoped = proposalRepository.findAwards("P-IN");

        assertThat(legacyAwardNumbers(unscoped)).containsExactly("HUB-00001", "IN-00001", "OUT-00001");
        assertThat(asList(CENTRAL, access -> proposalLegacy(access).findAwards("P-IN"))).isEqualTo(unscoped);
        assertThat(asList(OFF, access -> proposalLegacy(access).findAwards("P-IN"))).isEqualTo(unscoped);
    }

    @Test
    void legacyProposalAwards_sqlAndPerRecordDecisionAgree() {
        for (Caller caller : List.of(DEPT, MIXED, CENTRAL)) {
            List<ProposalAwardResponse> all = proposalRepository.findAwards("P-IN");
            as(caller, access -> {
                assertThat(proposalLegacy(access).findAwards("P-IN"))
                        .as("%s: legacy awards vs canSeeAwardNumber", caller)
                        .containsExactlyElementsOf(keep(all, row -> access.canSeeAwardNumber(row.awardNumber())));
                return null;
            });
        }
    }

    // =====================================================================
    // GET /api/awards/{n}/proposals and /api/awards/{n}/funding (legacy)
    // =====================================================================

    @Test
    void legacyAwardProposals_departmentCallerSeesOnlyInUnitProposalsAndCountsOnlyThem() {
        List<Long> ids = as(DEPT, access ->
                award(access).findCurrentProposals("HUB-00001").stream()
                        .map(AwardProposalResponse::proposalId).toList());
        long count = as(DEPT, access -> award(access).findCurrentFunding("HUB-00001").linkedProposalCount());

        assertThat(ids).as("expected /api/awards/HUB-00001/proposals to omit 8002").containsExactly(8001L);
        // A count of 2 would disclose the hidden relation as surely as a row.
        assertThat(count).as("expected /api/awards/HUB-00001/funding to count only 1").isEqualTo(1L);
    }

    @Test
    void legacyAwardProposals_centralAndEnforcementOffSeeEveryRelation() {
        List<AwardProposalResponse> unscoped = awardRepository.findCurrentProposals("HUB-00001");

        assertThat(unscoped).hasSize(2);
        assertThat(asList(CENTRAL, access -> award(access).findCurrentProposals("HUB-00001"))).isEqualTo(unscoped);
        assertThat(asList(OFF, access -> award(access).findCurrentProposals("HUB-00001"))).isEqualTo(unscoped);
        long offCount = as(OFF, access -> award(access).findCurrentFunding("HUB-00001").linkedProposalCount());
        assertThat(offCount).isEqualTo(2L);
    }

    @Test
    void legacyAwardProposals_sqlAndPerRecordDecisionAgree() {
        for (Caller caller : List.of(DEPT, MIXED, CENTRAL)) {
            List<AwardProposalResponse> all = awardRepository.findCurrentProposals("HUB-00001");
            as(caller, access -> {
                assertThat(award(access).findCurrentProposals("HUB-00001"))
                        .as("%s: legacy award proposals vs canSeeProposal", caller)
                        .containsExactlyElementsOf(keep(all, row -> access.canSeeProposal(row.proposalId())));
                return null;
            });
        }
    }

    // =====================================================================
    // Detail, attachments and search for an omitted related record
    // =====================================================================

    @Test
    void anOmittedRelatedRecordsDetailAndAttachmentsStay404() {
        // P-OUT (omitted from DEPT's funding-proposals)
        as(DEPT, access -> {
            assertNotFound(() -> proposalV1(access).findSummary(8002L));
            assertNotFound(() -> proposalV1(access).findAttachments(8002L));
            assertNotFound(() -> proposalV1(access).downloadAttachment(8002L, 1L));
            // OUT-00001 (omitted from DEPT's funded-awards)
            assertNotFound(() -> award(access).findFamily("OUT-00001"));
            assertNotFound(() -> award(access).findAttachments(2001L, 0, 25));
            assertNotFound(() -> award(access).downloadAttachment(2001L, 1L));
            return null;
        });
        // SUB-B and negotiation 502 (omitted from MIXED's lists)
        as(MIXED, access -> {
            SubawardArchiveService subawards = new SubawardArchiveService(subawardRepository, null, access);
            assertNotFound(() -> subawards.findWorkspace(9102L));
            assertNotFound(() -> subawards.findAttachments(9102L));
            assertNotFound(() -> subawards.downloadAttachment(9102L, 1L));
            NegotiationArchiveService negotiations =
                    new NegotiationArchiveService(negotiationRepository, null, access);
            assertNotFound(() -> negotiations.findWorkspace(502L));
            assertNotFound(() -> negotiations.findAttachments(502L));
            assertNotFound(() -> negotiations.downloadAttachment(502L, 1L));
            return null;
        });
    }

    @Test
    void anOmittedRelatedRecordIsAlsoAbsentFromSearch() {
        List<String> families = as(DEPT, access ->
                proposalLegacy(access).findFamilies("", 50).stream()
                        .map(row -> row.proposalNumber()).toList());

        assertThat(families).contains("P-IN").doesNotContain("P-OUT");
    }

    // --- harness -----------------------------------------------------------

    /**
     * Runs {@code work} as {@code caller} on a fresh request, through a real
     * RecordAuthorizationService. The outcome is cached per request, so each
     * caller gets its own request scope.
     */
    private static <T> T as(Caller caller, Function<RecordAuthorizationService, T> work) {
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(new MockHttpServletRequest()));
        try {
            return work.apply(authorization(caller));
        } finally {
            RequestContextHolder.resetRequestAttributes();
        }
    }

    /** {@link #as} for a list result, so assertThat resolves unambiguously. */
    private static <T> List<T> asList(Caller caller, Function<RecordAuthorizationService, List<T>> work) {
        return as(caller, work);
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
                    spec.unit(), false, null, null, null, null, spec.awardNumber()));
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
                IoSqlStrategy.NONE,
                IdentityEnrollment.DISABLED,
                clock);
    }

    private static AwardArchiveService award(RecordAuthorizationService access) {
        return new AwardArchiveService(awardRepository, null, access);
    }

    private static ProposalArchiveV1Service proposalV1(ProposalRecordAccess access) {
        return new ProposalArchiveV1Service(proposalV1Repository, null, provider(access), null);
    }

    private static ProposalArchiveService proposalLegacy(ProposalRecordAccess access) {
        return new ProposalArchiveService(proposalRepository, provider(access), null);
    }

    private static org.springframework.beans.factory.ObjectProvider<ProposalRecordAccess> provider(
            ProposalRecordAccess access) {
        StaticListableBeanFactory beans = new StaticListableBeanFactory();
        beans.addBean("proposalRecordAccess", access);
        return beans.getBeanProvider(ProposalRecordAccess.class);
    }

    private static void assertNotFound(Runnable call) {
        assertThatThrownBy(call::run).isInstanceOf(NoSuchElementException.class);
    }

    private static <T> List<T> keep(List<T> rows, Predicate<T> allowed) {
        return rows.stream().filter(allowed).toList();
    }

    private static List<String> proposalNumbers(List<AwardFundingProposalResponse> rows) {
        return rows.stream().map(AwardFundingProposalResponse::proposalNumber).toList();
    }

    private static List<String> subawardCodes(List<AwardFundingSubawardResponse> rows) {
        return rows.stream().map(AwardFundingSubawardResponse::subawardCode).toList();
    }

    private static List<Long> negotiationIds(List<AwardAssociatedNegotiationResponse> rows) {
        return rows.stream().map(AwardAssociatedNegotiationResponse::negotiationId).toList();
    }

    private static List<String> fundedAwardNumbers(List<ProposalFundedAwardResponse> rows) {
        return rows.stream().map(ProposalFundedAwardResponse::awardNumber).toList();
    }

    private static List<String> legacyAwardNumbers(List<ProposalAwardResponse> rows) {
        return rows.stream().map(ProposalAwardResponse::awardNumber).toList();
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
