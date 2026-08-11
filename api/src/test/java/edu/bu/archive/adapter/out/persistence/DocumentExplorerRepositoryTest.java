package edu.bu.archive.adapter.out.persistence;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/*
 * SQL-lock tests for the Kuali Document Explorer's fixed four-module
 * union, mirroring this repo's own established convention for
 * verifying query correctness without a real database connection (see
 * AwardArchiveRepositoryTest, DocumentSearchRepositoryTest). Status
 * normalization is a literal SQL CASE expression, not Java logic, so
 * its correctness is verified here as exact text assertions against
 * the real native-status values discovered in
 * docs/architecture/KUALI_DOCUMENT_EXPLORER_DESIGN.md §2.
 */
class DocumentExplorerRepositoryTest {

    private JdbcClient jdbc;
    private JdbcClient.StatementSpec statement;

    private DocumentExplorerRepository newRepository() {
        jdbc = mock(JdbcClient.class);
        statement = mock(JdbcClient.StatementSpec.class);
        when(jdbc.sql(anyString())).thenReturn(statement);
        when(statement.param(anyString(), any())).thenReturn(statement);
        return new DocumentExplorerRepository(jdbc);
    }

    private String capturedSql() {
        return org.mockito.Mockito.mockingDetails(jdbc)
                .getInvocations()
                .stream()
                .filter(invocation -> invocation.getMethod().getName().equals("sql"))
                .map(invocation -> (String) invocation.getArgument(0))
                .findFirst()
                .orElseThrow()
                .replaceAll("\\s+", " ");
    }

    private DocumentExplorerFilter noOpFilter() {
        return new DocumentExplorerFilter(
                "", "", "", "", "", "", "", false,
                "", "", "", false, "", "", null, null, "documentNumber"
        );
    }

    @SuppressWarnings("unchecked")
    private void stubSearch() {
        JdbcClient.MappedQuerySpec<DocumentExplorerRow> query = mock(JdbcClient.MappedQuerySpec.class);
        when(statement.query(DocumentExplorerRow.class)).thenReturn(query);
        when(query.list()).thenReturn(List.of());
    }

    // --- Scenario 27: IRB is absent from filters and counts ---

    @Test
    void unionExcludesIrbBudgetTimeAndMoneyPendingTransactionSapAndAttachments() {
        DocumentExplorerRepository repository = newRepository();
        stubSearch();
        repository.search(noOpFilter(), 25, 0);

        String sql = capturedSql();
        assertThat(sql)
                .doesNotContain("irb_protocol")
                .doesNotContain("archive.award_budget")
                .doesNotContain("archive.time_and_money_document")
                .doesNotContain("archive.pending_transaction")
                .doesNotContain("archive.award_transmission")
                .doesNotContain("archive.award_attachment")
                .doesNotContain("archive.attachment_object");
    }

    @Test
    void unionContainsExactlyTheFourApprovedModuleBranches() {
        DocumentExplorerRepository repository = newRepository();
        stubSearch();
        repository.search(noOpFilter(), 25, 0);

        String sql = capturedSql();
        assertThat(sql)
                .contains("'AWARD' AS module")
                .contains("FROM archive.award_version av")
                .contains("'PROPOSAL'")
                .contains("FROM archive.proposal_version pv")
                .contains("'NEGOTIATION'")
                .contains("FROM archive.negotiation n")
                .contains("'SUBAWARD'")
                .contains("FROM archive.subaward s");
    }

    // --- Scenario 2/3: Award native and normalized statuses; ambiguous -> UNKNOWN ---

    @Test
    void awardStatusMappingMatchesTheApprovedTableAndAllElseCasesAreUnknown() {
        DocumentExplorerRepository repository = newRepository();
        stubSearch();
        repository.search(noOpFilter(), 25, 0);

        String sql = capturedSql();
        assertThat(sql)
                .contains("CASE av.status_description")
                .contains("WHEN 'Approved Award' THEN 'ACTIVE'")
                .contains("WHEN 'Closed' THEN 'CLOSED'")
                .contains("WHEN 'Cancelled' THEN 'CANCELLED'")
                // Ambiguous statuses (PAFO/OSP (Closing), Pre-Close,
                // Compliance Hold, Spending Hold, Pre-Award Billable/Not
                // Billable, Do Not Use This Status) must NEVER appear as
                // a WHEN clause - they must all fall through to the
                // ELSE 'UNKNOWN' branch, never guessed.
                .doesNotContain("PAFO/OSP")
                .doesNotContain("Pre-Close")
                .doesNotContain("Compliance Hold")
                .doesNotContain("Spending Hold")
                .doesNotContain("Pre-Award");
        // Exactly one ELSE 'UNKNOWN' per module CASE (4 modules).
        assertThat(sql.split("ELSE 'UNKNOWN'", -1)).hasSize(5);
    }

    @Test
    void proposalStatusMappingMatchesTheApprovedTable() {
        DocumentExplorerRepository repository = newRepository();
        stubSearch();
        repository.search(noOpFilter(), 25, 0);

        String sql = capturedSql();
        assertThat(sql)
                .contains("CASE pv.status_description")
                .contains("WHEN 'Pending' THEN 'PENDING'")
                .contains("WHEN 'Funded' THEN 'ACTIVE'")
                .contains("WHEN 'Not Funded' THEN 'ARCHIVED'")
                .contains("WHEN 'Withdrawn' THEN 'ARCHIVED'")
                .contains("WHEN 'Deactivated Record' THEN 'ARCHIVED'");
    }

    @Test
    void negotiationStatusMappingMatchesTheApprovedTableAndOnHoldIsUnknown() {
        DocumentExplorerRepository repository = newRepository();
        stubSearch();
        repository.search(noOpFilter(), 25, 0);

        String sql = capturedSql();
        assertThat(sql)
                .contains("CASE n.negotiation_status_description")
                .contains("WHEN 'Fully Executed' THEN 'ACTIVE'")
                .contains("WHEN 'Abandoned' THEN 'CANCELLED'")
                .contains("WHEN 'In Negotiation' THEN 'PENDING'")
                .doesNotContain("WHEN 'On Hold'")
                .doesNotContain("WHEN 'Limited Issues'");
    }

    @Test
    void subawardStatusMappingMatchesTheApprovedTable() {
        DocumentExplorerRepository repository = newRepository();
        stubSearch();
        repository.search(noOpFilter(), 25, 0);

        String sql = capturedSql();
        assertThat(sql)
                .contains("CASE s.status_description")
                .contains("WHEN '07. Executed' THEN 'ACTIVE'")
                .contains("WHEN '10. Cancelled' THEN 'CANCELLED'")
                .contains("WHEN '08. Closed' THEN 'CLOSED'")
                .contains("WHEN '05. Sent to subrecipient' THEN 'PENDING'");
    }

    // --- Scenario 4: exact native-status filtering, never a substring scan ---

    @Test
    void nativeStatusFilterIsExactEqualityNeverIlike() {
        DocumentExplorerRepository repository = newRepository();
        stubSearch();
        repository.search(noOpFilter(), 25, 0);

        String sql = capturedSql();
        assertThat(sql).contains("native_status_description = :nativeStatus");
        assertThat(sql).doesNotContain("native_status_description ILIKE");
    }

    // --- Scenario 5/6/19/20: lead-unit default, any-unit option, combinations ---

    @Test
    void unitFilterDefaultsToLeadUnitAndAnyUnitIsAnExplicitExists() {
        DocumentExplorerRepository repository = newRepository();
        stubSearch();
        repository.search(noOpFilter(), 25, 0);

        String sql = capturedSql();
        assertThat(sql)
                .contains("lead_unit_number = :unitNumber")
                .contains(":includeAnyUnit = TRUE")
                .contains("EXISTS ( SELECT 1 FROM award_any_units dau")
                .contains("dau.unit_number = :unitNumber");
    }

    @Test
    void anyUnitMembershipIsSourcedOnlyFromAwardPersonUnitNeverFabricatedForOtherModules() {
        DocumentExplorerRepository repository = newRepository();
        stubSearch();
        repository.search(noOpFilter(), 25, 0);

        String sql = capturedSql();
        assertThat(sql)
                .contains("award_any_units AS ( SELECT av.workflow_document_number AS document_number, apu.unit_number FROM archive.award_person_unit apu");
    }

    // --- Regression: joining archive.award_version by award_number
    // alone (without is_primary_current) fans a single Negotiation/
    // Subaward row out into one row per historical Award version -
    // caught live against dev data (204713-00001 alone has 544
    // versions), where it inflated the NEGOTIATION facet from 10,775
    // to 12,236 rows before this fix. ---

    @Test
    void negotiationAssociatedAwardJoinIsScopedToTheCurrentAwardVersionOnly() {
        DocumentExplorerRepository repository = newRepository();
        stubSearch();
        repository.search(noOpFilter(), 25, 0);

        String sql = capturedSql();
        int cteStart = sql.indexOf("negotiation_associated_award AS (");
        int cteEnd = sql.indexOf("),", cteStart);
        String cteText = sql.substring(cteStart, cteEnd);
        assertThat(cteText)
                .contains("JOIN archive.award_version av ON av.award_number = n.associated_document_id")
                .contains("av.is_primary_current = TRUE");
    }

    @Test
    void subawardSponsorJoinIsScopedToTheCurrentAwardVersionOnly() {
        DocumentExplorerRepository repository = newRepository();
        stubSearch();
        repository.search(noOpFilter(), 25, 0);

        String sql = capturedSql();
        int cteStart = sql.indexOf("subaward_sponsor AS (");
        int cteEnd = sql.indexOf("negotiation_associated_award", cteStart);
        String cteText = sql.substring(cteStart, cteEnd);
        assertThat(cteText)
                .contains("JOIN archive.award_version av ON av.award_number = sf.award_number")
                .contains("av.is_primary_current = TRUE");
    }

    // --- Scenario 7/10: Award PI filtering, Negotiator never labeled PI ---

    @Test
    void piOnlyFilterOnlyConstrainsAwardModuleRows() {
        DocumentExplorerRepository repository = newRepository();
        stubSearch();
        repository.search(noOpFilter(), 25, 0);

        String sql = capturedSql();
        assertThat(sql).contains(
                ":piOnly = FALSE OR module <> 'AWARD' OR primary_person_role IN ('PI', 'MPI')"
        );
    }

    @Test
    void negotiationBranchAlwaysAssignsTheLiteralNegotiatorRoleNeverPi() {
        DocumentExplorerRepository repository = newRepository();
        stubSearch();
        repository.search(noOpFilter(), 25, 0);

        String sql = capturedSql();
        // Extract just the NEGOTIATION SELECT branch to prove 'PI'
        // never appears as its role literal.
        int negotiationStart = sql.indexOf("'NEGOTIATION',");
        int negotiationEnd = sql.indexOf("UNION ALL", negotiationStart);
        String negotiationBranch = sql.substring(negotiationStart, negotiationEnd);
        assertThat(negotiationBranch).contains("'Negotiator'");
        assertThat(negotiationBranch).doesNotContain("'PI'");
        assertThat(negotiationBranch).doesNotContain("'MPI'");
    }

    // --- Scenario 8: Proposal denormalized PI filtering ---

    @Test
    void proposalPrimaryPersonRoleIsPrincipalInvestigatorOnlyWhenPopulated() {
        DocumentExplorerRepository repository = newRepository();
        stubSearch();
        repository.search(noOpFilter(), 25, 0);

        String sql = capturedSql();
        assertThat(sql).contains(
                "CASE WHEN pv.principal_investigator_name IS NOT NULL THEN 'Principal Investigator' ELSE NULL END"
        );
    }

    // --- Scenario 11/12: Subaward site investigator + contacts ---

    @Test
    void subawardPrimaryPersonIsSiteInvestigatorAndContactsAreCounted() {
        DocumentExplorerRepository repository = newRepository();
        stubSearch();
        repository.search(noOpFilter(), 25, 0);

        String sql = capturedSql();
        assertThat(sql)
                .contains("s.site_investigator::text")
                .contains("'Site Investigator'")
                .contains("subaward_contact_counts AS ( SELECT subaward_id, COUNT(*) AS contact_count FROM archive.subaward_contact");
    }

    // --- Scenario 13/14/15: subrecipient org, verified sponsor, no duplication ---

    @Test
    void subawardSubrecipientOrganizationIsItsOwnDirectColumnNeverTreatedAsSponsor() {
        DocumentExplorerRepository repository = newRepository();
        stubSearch();
        repository.search(noOpFilter(), 25, 0);

        String sql = capturedSql();
        assertThat(sql).contains("s.organization_id");
        // organization_id maps to subrecipient_organization_id, not sponsor_code.
        int subawardStart = sql.indexOf("'SUBAWARD',");
        String subawardBranch = sql.substring(subawardStart);
        assertThat(subawardBranch.indexOf("ss.sponsor_code"))
                .isLessThan(subawardBranch.indexOf("s.organization_id"));
    }

    @Test
    void subawardSponsorIsResolvedOnlyThroughVerifiedFundingAwardJoinNeverTheSparseColumn() {
        DocumentExplorerRepository repository = newRepository();
        stubSearch();
        repository.search(noOpFilter(), 25, 0);

        String sql = capturedSql();
        assertThat(sql)
                .contains("subaward_sponsor AS (")
                .contains("JOIN archive.subaward_funding sf ON sf.subaward_id = s.subaward_id")
                .contains("JOIN archive.award_version av ON av.award_number = sf.award_number")
                .doesNotContain("s.award_sponsor_name");
    }

    @Test
    void subawardSponsorAggregationGroupsByOneSubawardIdNeverDuplicatingTheDocumentRow() {
        DocumentExplorerRepository repository = newRepository();
        stubSearch();
        repository.search(noOpFilter(), 25, 0);

        String sql = capturedSql();
        assertThat(sql).contains("GROUP BY s.subaward_id");
        assertThat(sql).contains("sponsor_count");
    }

    // --- Scenario 16/17/21: document number, business record number, person name ---

    @Test
    void documentNumberBusinessRecordNumberAndPersonNameUseParameterizedIlike() {
        DocumentExplorerRepository repository = newRepository();
        stubSearch();
        repository.search(noOpFilter(), 25, 0);

        String sql = capturedSql();
        assertThat(sql)
                .contains("document_number ILIKE :documentNumberPattern")
                .contains("business_record_number ILIKE :businessRecordNumberPattern")
                .contains("primary_person_name ILIKE :personNamePattern");
    }

    // --- Scenario 22: pagination and stable sorting, fixed allowlist ---

    @Test
    void sortIsResolvedFromAFixedAllowlistNeverARawColumnName() {
        DocumentExplorerRepository repository = newRepository();
        stubSearch();

        DocumentExplorerFilter titleSort = new DocumentExplorerFilter(
                "", "", "", "", "", "", "", false, "", "", "", false, "", "",
                null, null, "title"
        );
        repository.search(titleSort, 25, 0);
        assertThat(capturedSql()).contains("ORDER BY title NULLS LAST, document_number");
    }

    @Test
    void defaultSortIsStableByDocumentNumberThenModule() {
        DocumentExplorerRepository repository = newRepository();
        stubSearch();
        repository.search(noOpFilter(), 25, 0);
        assertThat(capturedSql()).contains("ORDER BY document_number, module");
    }

    // --- 24: SQL injection text treated as data ---

    @Test
    void everyFilterValueIsBoundAsAParameterNeverConcatenated() {
        DocumentExplorerRepository repository = newRepository();
        stubSearch();

        DocumentExplorerFilter malicious = new DocumentExplorerFilter(
                "'; DROP TABLE archive.award_version; --",
                "", "", "", "", "", "", false, "", "", "", false, "", "",
                LocalDate.of(2020, 1, 1), LocalDate.of(2021, 1, 1), "documentNumber"
        );
        repository.search(malicious, 25, 0);

        // The malicious string never appears literally in the SQL text
        // - it only ever reaches the database as a bound parameter.
        assertThat(capturedSql()).doesNotContain("DROP TABLE");
        org.mockito.Mockito.verify(statement).param("documentNumber", "'; DROP TABLE archive.award_version; --");
    }

    // --- count() and moduleFacets() mirror search()'s filter binding ---

    @Test
    void countUsesTheSameCteAndFilterWithNoLimitOffset() {
        DocumentExplorerRepository repository = newRepository();
        @SuppressWarnings("unchecked")
        JdbcClient.MappedQuerySpec<Long> query = mock(JdbcClient.MappedQuerySpec.class);
        when(statement.query(Long.class)).thenReturn(query);
        when(query.single()).thenReturn(7L);

        long result = repository.count(noOpFilter());

        assertThat(result).isEqualTo(7L);
        assertThat(capturedSql())
                .contains("SELECT COUNT(*) FROM documents")
                .doesNotContain("LIMIT")
                .doesNotContain("OFFSET");
    }

    @Test
    void moduleFacetsGroupsByModuleAgainstTheSameFilteredSet() {
        DocumentExplorerRepository repository = newRepository();
        @SuppressWarnings("unchecked")
        JdbcClient.MappedQuerySpec<DocumentExplorerRepository.ModuleFacetRow> query =
                mock(JdbcClient.MappedQuerySpec.class);
        when(statement.query(DocumentExplorerRepository.ModuleFacetRow.class)).thenReturn(query);
        when(query.list()).thenReturn(List.of(new DocumentExplorerRepository.ModuleFacetRow("AWARD", 49827L)));

        List<DocumentExplorerRepository.ModuleFacetRow> facets = repository.moduleFacets(noOpFilter());

        assertThat(facets).hasSize(1);
        assertThat(capturedSql()).contains("GROUP BY module");
    }
}
