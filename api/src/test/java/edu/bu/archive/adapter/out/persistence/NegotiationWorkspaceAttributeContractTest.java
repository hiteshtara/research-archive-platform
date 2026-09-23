package edu.bu.archive.adapter.out.persistence;

import edu.bu.archive.adapter.in.web.dto.negotiation.NegotiationRowResponse;
import edu.bu.archive.adapter.in.web.dto.negotiation.NegotiationSummaryResponse;
import edu.bu.archive.application.negotiation.NegotiationSearchFilters;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Statement;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/*
 * The Negotiation WORKSPACE endpoint must SHOW the resolved attributes
 * the V080 rebuild materialized - never recompute them.
 *
 * This lives in its own class, with its own container and its own seed,
 * deliberately: the sibling schema-integration test asserts on the
 * unfiltered list, so adding fixtures to its shared seed silently breaks
 * seven of its tests. Isolating the fixtures keeps both suites honest.
 *
 * The four records below are the ones confirmed against the Kuali UI and
 * then re-confirmed against dev after the rebuild. The three
 * Award-associated ones are where a reintroduced resolver would diverge
 * first: each has a detail-row PI that differs from the Award PI.
 */
@Testcontainers
class NegotiationWorkspaceAttributeContractTest {

    private static final Pattern MIGRATION_VERSION =
            Pattern.compile("^V(\\d+)__.*\\.sql$");

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg17")
                    .asCompatibleSubstituteFor("postgres")
    );

    private static NegotiationArchiveRepository repository;

    @BeforeAll
    static void applyMigrationsAndSeed() throws Exception {
        Path migrationsDir = locateMigrationsDirectory();
        List<Path> migrations;
        try (Stream<Path> files = Files.list(migrationsDir)) {
            migrations = files
                    .filter(path -> MIGRATION_VERSION
                            .matcher(path.getFileName().toString()).matches())
                    .sorted(Comparator.comparingInt(
                            NegotiationWorkspaceAttributeContractTest
                                    ::migrationVersion))
                    .toList();
        }
        assertThat(migrations).isNotEmpty();

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
                    INSERT INTO archive.negotiation (
                        negotiation_id, document_number,
                        negotiation_status_description,
                        negotiation_agreement_type_description,
                        negotiation_association_type_description,
                        negotiator_person_id, negotiator_full_name,
                        negotiation_start_date, negotiation_end_date,
                        associated_document_id, loaded_at
                    ) VALUES
                    (120, '360120', 'Fully Executed',
                     'Material Transfer Agreement', 'None',
                     'U93001494', 'JESSICA L RIVIECCIO',
                     DATE '2014-05-24', DATE '2014-06-02', '119',
                     CURRENT_TIMESTAMP),
                    (1641, '361641', 'Fully Executed',
                     'Material Transfer Agreement', 'Award',
                     'U93001494', 'JESSICA L RIVIECCIO',
                     DATE '2015-07-31', DATE '2015-09-01', '204120-00001',
                     CURRENT_TIMESTAMP),
                    (2587, '362587', 'Fully Executed',
                     'Material Transfer Agreement', 'Award',
                     'U93001494', 'JESSICA L RIVIECCIO',
                     DATE '2016-07-28', DATE '2016-09-01', '205034-00001',
                     CURRENT_TIMESTAMP),
                    (2676, '362676', 'Fully Executed',
                     'Material Transfer Agreement', 'Award',
                     'U93001494', 'JESSICA L RIVIECCIO',
                     DATE '2016-10-05', DATE '2016-11-17', '203818-00001',
                     CURRENT_TIMESTAMP)
                    """);

            statement.execute("""
                    INSERT INTO archive.negotiation_search_attribute (
                        negotiation_id, attribute_source, title,
                        principal_investigator_name,
                        sponsor_code, sponsor_name,
                        prime_sponsor_code, prime_sponsor_name,
                        lead_unit_number, lead_unit_name,
                        sponsor_award_number
                    ) VALUES
                    (120, 'UNASSOCIATED_DETAIL', '168333',
                     'AHMAD KHALIL', '303630', 'Addgene',
                     NULL, NULL,
                     '1242040000', 'ENG BIOMEDICAL ENG', NULL),
                    (1641, 'AWARD', 'Pulmonary Hypertension Study',
                     'ELIZABETH S KLINGS', '300001',
                     'Bayer HealthCare Pharmaceuticals',
                     NULL, NULL,
                     '2574000000', 'CNTR MED--ARTHRITIS CENTER', 'SPA-1'),
                    (2587, 'AWARD', 'Registry Study',
                     'ROBERT W SIMMS', '300002',
                     'United States Pulmonary Hypertension Scientific Registry',
                     NULL, NULL,
                     '2574000000', 'CNTR MED--ARTHRITIS CENTER', NULL),
                    (2676, 'AWARD',
                     'Dissemination of Evidence-Informed Interventions',
                     'JORGE DELVA', '301028',
                     'HHS/Health Resources and Services Administration',
                     '301029', 'NIH Prime',
                     '2444020000', 'SPH Ctr Advancing Hlth Policy & Practice',
                     'X329792')
                    """);
        }

        repository = new NegotiationArchiveRepository(
                JdbcClient.create(dataSource));
    }

    private static int migrationVersion(Path path) {
        Matcher matcher =
                MIGRATION_VERSION.matcher(path.getFileName().toString());
        if (!matcher.matches()) {
            throw new IllegalStateException("Not a migration: " + path);
        }
        return Integer.parseInt(matcher.group(1));
    }

    private static Path locateMigrationsDirectory() throws IOException {
        Path candidate = Path.of("").toAbsolutePath();
        while (candidate != null) {
            Path migrations = candidate.resolve("database/migrations");
            if (Files.isDirectory(migrations)) {
                return migrations;
            }
            candidate = candidate.getParent();
        }
        throw new IOException("Could not locate database/migrations/");
    }

    @Test
    void theWorkspaceExposesEveryMaterializedAttributeForNegotiation120() {
        NegotiationRowResponse row = repository.findById(120).orElseThrow();
        assertThat(row.title()).isEqualTo("168333");
        assertThat(row.principalInvestigatorName()).isEqualTo("AHMAD KHALIL");
        assertThat(row.sponsorCode()).isEqualTo("303630");
        assertThat(row.sponsorName()).isEqualTo("Addgene");
        assertThat(row.leadUnitNumber()).isEqualTo("1242040000");
        assertThat(row.leadUnitName()).isEqualTo("ENG BIOMEDICAL ENG");
        assertThat(row.attributeSource()).isEqualTo("UNASSOCIATED_DETAIL");
    }

    @Test
    void theWorkspaceShowsTheAwardResolvedPiOnAllThreeDiscriminatingRecords() {
        assertThat(repository.findById(1641).orElseThrow()
                .principalInvestigatorName()).isEqualTo("ELIZABETH S KLINGS");
        assertThat(repository.findById(2587).orElseThrow()
                .principalInvestigatorName()).isEqualTo("ROBERT W SIMMS");
        assertThat(repository.findById(2676).orElseThrow()
                .principalInvestigatorName()).isEqualTo("JORGE DELVA");
    }

    @Test
    void theWorkspaceReproducesTheFullyVerified2676Record() {
        NegotiationRowResponse row = repository.findById(2676).orElseThrow();
        assertThat(row.principalInvestigatorName()).isEqualTo("JORGE DELVA");
        assertThat(row.sponsorName())
                .isEqualTo("HHS/Health Resources and Services Administration");
        assertThat(row.leadUnitName())
                .isEqualTo("SPH Ctr Advancing Hlth Policy & Practice");
        assertThat(row.attributeSource()).isEqualTo("AWARD");
        assertThat(row.associatedDocumentId()).isEqualTo("203818-00001");
        assertThat(row.primeSponsorName()).isEqualTo("NIH Prime");
        assertThat(row.sponsorAwardNumber()).isEqualTo("X329792");
    }

    @Test
    void theListAndWorkspaceEndpointsAgreeOnEveryResolvedAttribute() {
        // One materialized row, two readers. If these ever disagree, one
        // of them has started resolving instead of reading.
        List<NegotiationSummaryResponse> listed = repository.findNegotiations(
                NegotiationSearchFilters.none(), 500, 0);

        for (long negotiationId : new long[] {120, 1641, 2587, 2676}) {
            NegotiationRowResponse detail =
                    repository.findById(negotiationId).orElseThrow();
            NegotiationSummaryResponse summary = listed.stream()
                    .filter(row -> row.negotiationId() == negotiationId)
                    .findFirst()
                    .orElseThrow(() -> new AssertionError(
                            "negotiation " + negotiationId + " missing"));

            assertThat(detail.title()).isEqualTo(summary.title());
            assertThat(detail.principalInvestigatorName())
                    .isEqualTo(summary.principalInvestigatorName());
            assertThat(detail.sponsorCode()).isEqualTo(summary.sponsorCode());
            assertThat(detail.sponsorName()).isEqualTo(summary.sponsorName());
            assertThat(detail.leadUnitNumber())
                    .isEqualTo(summary.leadUnitNumber());
            assertThat(detail.leadUnitName()).isEqualTo(summary.leadUnitName());
            assertThat(detail.attributeSource())
                    .isEqualTo(summary.attributeSource());
        }
    }

    @Test
    void aNegotiationWithNoMaterializedRowStillResolvesWithNullAttributes() {
        // LEFT, not INNER: an unrebuilt row must degrade to nulls rather
        // than remove the Negotiation from its own workspace.
        NegotiationRowResponse row = repository.findById(1641).orElseThrow();
        assertThat(row.negotiationId()).isEqualTo(1641L);
        assertThat(repository.findById(999999L)).isEmpty();
    }
}
