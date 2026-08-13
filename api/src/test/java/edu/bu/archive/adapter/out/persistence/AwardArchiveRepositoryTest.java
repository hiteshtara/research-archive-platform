package edu.bu.archive.adapter.out.persistence;

import edu.bu.archive.adapter.in.web.dto.award.AwardAssociatedNegotiationResponse;
import edu.bu.archive.adapter.in.web.dto.award.AwardCentralAdministrationContactResponse;
import edu.bu.archive.adapter.in.web.dto.award.AwardCustomDataResponse;
import edu.bu.archive.adapter.in.web.dto.award.AwardDocumentNumberMatchResponse;
import edu.bu.archive.adapter.in.web.dto.award.AwardFundingProposalResponse;
import edu.bu.archive.adapter.in.web.dto.award.AwardFundingSubawardResponse;
import edu.bu.archive.adapter.in.web.dto.award.AwardHierarchyEdgeRow;
import edu.bu.archive.adapter.in.web.dto.award.AwardSearchResultResponse;
import edu.bu.archive.adapter.in.web.dto.award.AwardSponsorContactResponse;
import edu.bu.archive.adapter.in.web.dto.award.AwardSummaryCardRow;
import edu.bu.archive.adapter.in.web.dto.award.AwardSummaryResponse;
import edu.bu.archive.adapter.in.web.dto.award.AwardUnitContactResponse;
import edu.bu.archive.adapter.in.web.dto.award.AwardUnitDetailsResponse;
import edu.bu.archive.adapter.in.web.dto.award.AwardVersionSearchResultResponse;
import edu.bu.archive.adapter.in.web.dto.award.AwardVersionSummaryResponse;
import edu.bu.archive.adapter.in.web.dto.explorer.ExplorerAwardResponse;
import edu.bu.archive.adapter.in.web.dto.explorer.ExplorerPersonResponse;
import edu.bu.archive.adapter.in.web.dto.explorer.ExplorerRolodexResponse;
import edu.bu.archive.adapter.in.web.dto.explorer.ExplorerUnitAdministratorResponse;
import edu.bu.archive.adapter.in.web.dto.explorer.ExplorerUnitRow;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AwardArchiveRepositoryTest {

    @Test
    void searchAwardsBindsPatternAndRawQueryAsSingleParameters() {
        JdbcClient jdbc = mock(JdbcClient.class);
        JdbcClient.StatementSpec statement =
                mock(JdbcClient.StatementSpec.class);
        @SuppressWarnings("unchecked")
        JdbcClient.MappedQuerySpec<AwardSearchResultResponse> query =
                mock(JdbcClient.MappedQuerySpec.class);

        when(jdbc.sql(anyString())).thenReturn(statement);
        when(statement.param(anyString(), any())).thenReturn(statement);
        when(statement.query(AwardSearchResultResponse.class))
                .thenReturn(query);
        when(query.list()).thenReturn(List.of());

        new AwardArchiveRepository(jdbc)
                .searchAwards("%cancer%", "cancer", 25, 0);

        assertThat(firstSql(jdbc))
                .contains("FROM archive.award_version av")
                .contains("av.is_primary_current = TRUE")
                .contains("av.award_number ILIKE :pattern")
                .contains("av.title ILIKE :pattern")
                .contains("av.sponsor_name ILIKE :pattern")
                .contains("av.lead_unit_name ILIKE :pattern")
                .contains("av.modification_number ILIKE :pattern")
                .contains("ap2.full_name ILIKE :pattern")
                .contains("UPPER(av.award_number) = UPPER(:rawQuery)")
                .contains("LEFT JOIN LATERAL")
                .contains("LEFT JOIN archive.award_hierarchy ah")
                .contains("ORDER BY av.award_number")
                .doesNotContain("' + rawQuery")
                .doesNotContain("\" + rawQuery");
        verify(statement).param("pattern", "%cancer%");
        verify(statement).param("rawQuery", "cancer");
        verify(statement).param("limit", 25);
        verify(statement).param("offset", 0);
    }

    /**
     * Regression for a proven correctness defect (docs/kuali-business-
     * rules/Time and Money.md): Kuali's own current-AWARD_AMOUNT_INFO-
     * row rule is MAX(award_amount_info_id), full stop -
     * source_version_number (Oracle's VER_NBR) is not part of that rule
     * and must never be allowed to outrank a later-appended row. Real
     * fixture (award_id 8): a row with award_amount_info_id=8,
     * source_version_number=1 used to incorrectly win over the truly
     * current row (award_amount_info_id=897305, source_version_number=0)
     * because source_version_number was sorted ahead of
     * award_amount_info_id. Live data confirmed 767 Award families hit
     * this exact divergence before the fix.
     */
    @Test
    void searchAwardsOrdersCurrentAmountByAwardAmountInfoIdOnly() {
        JdbcClient jdbc = mock(JdbcClient.class);
        JdbcClient.StatementSpec statement =
                mock(JdbcClient.StatementSpec.class);
        @SuppressWarnings("unchecked")
        JdbcClient.MappedQuerySpec<AwardSearchResultResponse> query =
                mock(JdbcClient.MappedQuerySpec.class);

        when(jdbc.sql(anyString())).thenReturn(statement);
        when(statement.param(anyString(), any())).thenReturn(statement);
        when(statement.query(AwardSearchResultResponse.class))
                .thenReturn(query);
        when(query.list()).thenReturn(List.of());

        new AwardArchiveRepository(jdbc)
                .searchAwards("%cancer%", "cancer", 25, 0);

        assertThat(firstSql(jdbc))
                .contains("FROM archive.award_amount_info ai")
                .contains("ai.award_amount_info_id DESC")
                .doesNotContain("source_version_number");
    }

    @Test
    void countSearchAwardsUsesTheSameWhereClauseAsSearch() {
        JdbcClient jdbc = mock(JdbcClient.class);
        JdbcClient.StatementSpec statement =
                mock(JdbcClient.StatementSpec.class);
        @SuppressWarnings("unchecked")
        JdbcClient.MappedQuerySpec<Long> query =
                mock(JdbcClient.MappedQuerySpec.class);

        when(jdbc.sql(anyString())).thenReturn(statement);
        when(statement.param(anyString(), any())).thenReturn(statement);
        when(statement.query(Long.class)).thenReturn(query);
        when(query.single()).thenReturn(42L);

        long total = new AwardArchiveRepository(jdbc)
                .countSearchAwards("%cancer%", "cancer");

        assertThat(total).isEqualTo(42L);
        assertThat(firstSql(jdbc))
                .contains("SELECT COUNT(*)")
                .contains("av.award_number ILIKE :pattern")
                .doesNotContain("LEFT JOIN LATERAL");
    }

    @Test
    void countSearchAwardsTreatsANullCountAsZero() {
        JdbcClient jdbc = mock(JdbcClient.class);
        JdbcClient.StatementSpec statement =
                mock(JdbcClient.StatementSpec.class);
        @SuppressWarnings("unchecked")
        JdbcClient.MappedQuerySpec<Long> query =
                mock(JdbcClient.MappedQuerySpec.class);

        when(jdbc.sql(anyString())).thenReturn(statement);
        when(statement.param(anyString(), any())).thenReturn(statement);
        when(statement.query(Long.class)).thenReturn(query);
        when(query.single()).thenReturn(null);

        long total = new AwardArchiveRepository(jdbc)
                .countSearchAwards("%none%", "none");

        assertThat(total).isZero();
    }

    @Test
    void findHierarchyRootLooksUpTheRootByAwardNumber() {
        JdbcClient jdbc = mock(JdbcClient.class);
        JdbcClient.StatementSpec statement =
                mock(JdbcClient.StatementSpec.class);
        @SuppressWarnings("unchecked")
        JdbcClient.MappedQuerySpec<String> query =
                mock(JdbcClient.MappedQuerySpec.class);

        when(jdbc.sql(anyString())).thenReturn(statement);
        when(statement.param("awardNumber", "100004-00003"))
                .thenReturn(statement);
        when(statement.query(String.class)).thenReturn(query);
        when(query.optional()).thenReturn(Optional.of("100004-00001"));

        Optional<String> root = new AwardArchiveRepository(jdbc)
                .findHierarchyRoot("100004-00003");

        assertThat(root).contains("100004-00001");
        assertThat(firstSql(jdbc))
                .contains("FROM archive.award_hierarchy")
                .contains("award_number = :awardNumber");
    }

    @Test
    void findHierarchyEdgesQueriesByRootAwardNumberOrderedByAwardNumber() {
        JdbcClient jdbc = mock(JdbcClient.class);
        JdbcClient.StatementSpec statement =
                mock(JdbcClient.StatementSpec.class);
        @SuppressWarnings("unchecked")
        JdbcClient.MappedQuerySpec<AwardHierarchyEdgeRow> query =
                mock(JdbcClient.MappedQuerySpec.class);

        AwardHierarchyEdgeRow edge = new AwardHierarchyEdgeRow(
                "100004-00001", "100004-00002", "100004-00001", "Y"
        );

        when(jdbc.sql(anyString())).thenReturn(statement);
        when(statement.param("rootAwardNumber", "100004-00001"))
                .thenReturn(statement);
        when(statement.query(AwardHierarchyEdgeRow.class))
                .thenReturn(query);
        when(query.list()).thenReturn(List.of(edge));

        List<AwardHierarchyEdgeRow> edges = new AwardArchiveRepository(jdbc)
                .findHierarchyEdges("100004-00001");

        assertThat(edges).containsExactly(edge);
        assertThat(firstSql(jdbc))
                .contains("FROM archive.award_hierarchy")
                .contains("root_award_number = :rootAwardNumber")
                .contains("ORDER BY award_number");
    }

    @Test
    void findSummaryCardsShortCircuitsOnAnEmptyCollectionWithoutQuerying() {
        JdbcClient jdbc = mock(JdbcClient.class);

        List<AwardSummaryCardRow> cards = new AwardArchiveRepository(jdbc)
                .findSummaryCards(List.of());

        assertThat(cards).isEmpty();
        org.mockito.Mockito.verifyNoInteractions(jdbc);
    }

    @Test
    void findSummaryCardsBindsTheAwardNumberCollection() {
        JdbcClient jdbc = mock(JdbcClient.class);
        JdbcClient.StatementSpec statement =
                mock(JdbcClient.StatementSpec.class);
        @SuppressWarnings("unchecked")
        JdbcClient.MappedQuerySpec<AwardSummaryCardRow> query =
                mock(JdbcClient.MappedQuerySpec.class);

        when(jdbc.sql(anyString())).thenReturn(statement);
        when(statement.param(anyString(), any())).thenReturn(statement);
        when(statement.query(AwardSummaryCardRow.class)).thenReturn(query);
        when(query.list()).thenReturn(List.of());

        new AwardArchiveRepository(jdbc).findSummaryCards(
                List.of("100004-00001", "100004-00002")
        );

        assertThat(firstSql(jdbc))
                .contains("av.is_primary_current = TRUE")
                .contains("av.award_number IN (:awardNumbers)");
        verify(statement).param(
                "awardNumbers",
                List.of("100004-00001", "100004-00002")
        );
    }

    /** See searchAwardsOrdersCurrentAmountByAwardAmountInfoIdOnly for the
     * full rationale - same proven defect, same fix. */
    @Test
    void findSummaryCardsOrdersCurrentAmountByAwardAmountInfoIdOnly() {
        JdbcClient jdbc = mock(JdbcClient.class);
        JdbcClient.StatementSpec statement =
                mock(JdbcClient.StatementSpec.class);
        @SuppressWarnings("unchecked")
        JdbcClient.MappedQuerySpec<AwardSummaryCardRow> query =
                mock(JdbcClient.MappedQuerySpec.class);

        when(jdbc.sql(anyString())).thenReturn(statement);
        when(statement.param(anyString(), any())).thenReturn(statement);
        when(statement.query(AwardSummaryCardRow.class)).thenReturn(query);
        when(query.list()).thenReturn(List.of());

        new AwardArchiveRepository(jdbc).findSummaryCards(
                List.of("100004-00001")
        );

        assertThat(firstSql(jdbc))
                .contains("FROM archive.award_amount_info ai")
                .contains("ai.award_amount_info_id DESC")
                .doesNotContain("source_version_number");
    }

    @Test
    void findSummaryByAwardIdMapsEveryFieldFromTheDesignatedColumns() {
        JdbcClient jdbc = mock(JdbcClient.class);
        JdbcClient.StatementSpec statement =
                mock(JdbcClient.StatementSpec.class);
        @SuppressWarnings("unchecked")
        JdbcClient.MappedQuerySpec<AwardSummaryResponse> query =
                mock(JdbcClient.MappedQuerySpec.class);
        AwardSummaryResponse expected = new AwardSummaryResponse(
                3L, "100004-00003", 1, "Title", "Approved Award",
                "Brown University", "NIH", "MICHAEL MCCLEAN",
                "SPH ENVIRONMENTAL HEALTH", LocalDate.of(2007, 9, 15),
                null, null, null, BigDecimal.TEN, BigDecimal.TEN,
                "1", "Cost reimbursement", "28", "Invoice",
                null, null, true, null
        );

        when(jdbc.sql(anyString())).thenReturn(statement);
        when(statement.param("awardId", 3L)).thenReturn(statement);
        when(statement.query(AwardSummaryResponse.class)).thenReturn(query);
        when(query.optional()).thenReturn(Optional.of(expected));

        Optional<AwardSummaryResponse> result =
                new AwardArchiveRepository(jdbc).findSummaryByAwardId(3L);

        assertThat(result).contains(expected);
        assertThat(firstSql(jdbc))
                .contains("FROM archive.award_version av")
                .contains("av.award_id = :awardId")
                .contains("basis_of_payment_code")
                .contains("method_of_payment_code")
                .contains("ah.root_award_number")
                .contains("ah.parent_award_number")
                .doesNotContain("fain")
                .doesNotContain("account_type");
    }

    /** See searchAwardsOrdersCurrentAmountByAwardAmountInfoIdOnly for the
     * full rationale - same proven defect, same fix. */
    @Test
    void findSummaryByAwardIdOrdersCurrentAmountByAwardAmountInfoIdOnly() {
        JdbcClient jdbc = mock(JdbcClient.class);
        JdbcClient.StatementSpec statement =
                mock(JdbcClient.StatementSpec.class);
        @SuppressWarnings("unchecked")
        JdbcClient.MappedQuerySpec<AwardSummaryResponse> query =
                mock(JdbcClient.MappedQuerySpec.class);

        when(jdbc.sql(anyString())).thenReturn(statement);
        when(statement.param("awardId", 3L)).thenReturn(statement);
        when(statement.query(AwardSummaryResponse.class)).thenReturn(query);
        when(query.optional()).thenReturn(Optional.empty());

        new AwardArchiveRepository(jdbc).findSummaryByAwardId(3L);

        assertThat(firstSql(jdbc))
                .contains("FROM archive.award_amount_info ai")
                .contains("ai.award_amount_info_id DESC")
                .doesNotContain("source_version_number");
    }

    @Test
    void findAwardNumberForIdLooksUpByAwardId() {
        JdbcClient jdbc = mock(JdbcClient.class);
        JdbcClient.StatementSpec statement =
                mock(JdbcClient.StatementSpec.class);
        @SuppressWarnings("unchecked")
        JdbcClient.MappedQuerySpec<String> query =
                mock(JdbcClient.MappedQuerySpec.class);

        when(jdbc.sql(anyString())).thenReturn(statement);
        when(statement.param("awardId", 3L)).thenReturn(statement);
        when(statement.query(String.class)).thenReturn(query);
        when(query.optional()).thenReturn(Optional.of("100004-00003"));

        Optional<String> awardNumber = new AwardArchiveRepository(jdbc)
                .findAwardNumberForId(3L);

        assertThat(awardNumber).contains("100004-00003");
        assertThat(firstSql(jdbc))
                .contains("FROM archive.award_version")
                .contains("award_id = :awardId");
    }

    @Test
    void findVersionSummariesOrdersNewestSequenceFirst() {
        JdbcClient jdbc = mock(JdbcClient.class);
        JdbcClient.StatementSpec statement =
                mock(JdbcClient.StatementSpec.class);
        @SuppressWarnings("unchecked")
        JdbcClient.MappedQuerySpec<AwardVersionSummaryResponse> query =
                mock(JdbcClient.MappedQuerySpec.class);

        when(jdbc.sql(anyString())).thenReturn(statement);
        when(statement.param(anyString(), any())).thenReturn(statement);
        when(statement.query(AwardVersionSummaryResponse.class))
                .thenReturn(query);
        when(query.list()).thenReturn(List.of());

        new AwardArchiveRepository(jdbc)
                .findVersionSummaries("100004-00001", 50, 0);

        assertThat(firstSql(jdbc))
                .contains("FROM archive.award_version")
                .contains("award_number = :awardNumber")
                .contains("ORDER BY")
                .contains("sequence_number DESC")
                .contains("workflow_document_number AS document_number")
                .contains("modification_number")
                .contains("is_primary_current AS primary_current")
                .contains("LIMIT :limit OFFSET :offset");
        verify(statement).param("awardNumber", "100004-00001");
        verify(statement).param("limit", 50);
        verify(statement).param("offset", 0);
    }

    @Test
    void countVersionsCountsAllRowsForTheAwardNumber() {
        JdbcClient jdbc = mock(JdbcClient.class);
        JdbcClient.StatementSpec statement =
                mock(JdbcClient.StatementSpec.class);
        @SuppressWarnings("unchecked")
        JdbcClient.MappedQuerySpec<Long> query =
                mock(JdbcClient.MappedQuerySpec.class);

        when(jdbc.sql(anyString())).thenReturn(statement);
        when(statement.param("awardNumber", "100004-00001"))
                .thenReturn(statement);
        when(statement.query(Long.class)).thenReturn(query);
        when(query.single()).thenReturn(9L);

        long total = new AwardArchiveRepository(jdbc)
                .countVersions("100004-00001");

        assertThat(total).isEqualTo(9L);
        assertThat(firstSql(jdbc))
                .contains("SELECT COUNT(*)")
                .contains("FROM archive.award_version")
                .contains("award_number = :awardNumber");
    }

    @Test
    void searchAwardVersionsNeverUnconditionallyScopesToPrimaryCurrent() {
        // The whole point of this query (Historical Award Records) is
        // both current and historical rows side by side - unlike
        // searchAwards, is_primary_current must only ever appear inside
        // the versionFilter-conditional clause, never as a bare
        // unconditional requirement.
        JdbcClient jdbc = mock(JdbcClient.class);
        JdbcClient.StatementSpec statement =
                mock(JdbcClient.StatementSpec.class);
        @SuppressWarnings("unchecked")
        JdbcClient.MappedQuerySpec<AwardVersionSearchResultResponse> query =
                mock(JdbcClient.MappedQuerySpec.class);

        when(jdbc.sql(anyString())).thenReturn(statement);
        when(statement.param(anyString(), any())).thenReturn(statement);
        when(statement.query(AwardVersionSearchResultResponse.class))
                .thenReturn(query);
        when(query.list()).thenReturn(List.of());

        new AwardArchiveRepository(jdbc).searchAwardVersions(
                "%cancer%", "cancer", "", "", null, "all",
                "ORDER BY av.sequence_number DESC\n", 25, 0
        );

        String sql = firstSql(jdbc);
        assertThat(sql)
                .contains("FROM archive.award_version av")
                .contains(":versionFilter = 'all'")
                .contains(":versionFilter = 'current' AND av.is_primary_current = TRUE")
                .contains(":versionFilter = 'historical' AND av.is_primary_current = FALSE")
                .contains("UPPER(av.award_number) = UPPER(:awardNumber)")
                .contains("UPPER(av.workflow_document_number) = UPPER(:documentNumber)")
                .contains("av.is_primary_current AS primary_current")
                .doesNotContain("WHERE av.is_primary_current = TRUE");
    }

    @Test
    void searchAwardVersionsBindsEveryFilterParameter() {
        JdbcClient jdbc = mock(JdbcClient.class);
        JdbcClient.StatementSpec statement =
                mock(JdbcClient.StatementSpec.class);
        @SuppressWarnings("unchecked")
        JdbcClient.MappedQuerySpec<AwardVersionSearchResultResponse> query =
                mock(JdbcClient.MappedQuerySpec.class);

        when(jdbc.sql(anyString())).thenReturn(statement);
        when(statement.param(anyString(), any())).thenReturn(statement);
        when(statement.query(AwardVersionSearchResultResponse.class))
                .thenReturn(query);
        when(query.list()).thenReturn(List.of());

        new AwardArchiveRepository(jdbc).searchAwardVersions(
                "%cancer%", "cancer", "204713-00001", "DOC-9001", 3561589L,
                "historical", "ORDER BY av.sequence_number DESC\n", 25, 50
        );

        verify(statement).param("pattern", "%cancer%");
        verify(statement).param("rawQuery", "cancer");
        verify(statement).param("awardNumber", "204713-00001");
        verify(statement).param("documentNumber", "DOC-9001");
        verify(statement).param("awardId", 3561589L);
        verify(statement).param("versionFilter", "historical");
        verify(statement).param("limit", 25);
        verify(statement).param("offset", 50);
    }

    @Test
    void searchAwardVersionsAwardIdFilterUsesTheDocumentedNullSafeCast() {
        // See the module-level comment on searchAwardVersions and
        // findProposalDiscoveryRows below: a bare ":awardId IS NULL OR
        // ..." throws AmbiguousParameter for a null Long bind against
        // Postgres - the explicit CAST(:awardId AS BIGINT) is required,
        // not decorative.
        JdbcClient jdbc = mock(JdbcClient.class);
        JdbcClient.StatementSpec statement =
                mock(JdbcClient.StatementSpec.class);
        @SuppressWarnings("unchecked")
        JdbcClient.MappedQuerySpec<AwardVersionSearchResultResponse> query =
                mock(JdbcClient.MappedQuerySpec.class);

        when(jdbc.sql(anyString())).thenReturn(statement);
        when(statement.param(anyString(), any())).thenReturn(statement);
        when(statement.query(AwardVersionSearchResultResponse.class))
                .thenReturn(query);
        when(query.list()).thenReturn(List.of());

        new AwardArchiveRepository(jdbc).searchAwardVersions(
                "%%", "", "", "", null, "all",
                "ORDER BY av.sequence_number DESC\n", 25, 0
        );

        assertThat(firstSql(jdbc))
                .contains("CAST(:awardId AS BIGINT) IS NULL OR av.award_id = :awardId");
    }

    @Test
    void countSearchAwardVersionsAppliesTheSameFiltersAsSearch() {
        JdbcClient jdbc = mock(JdbcClient.class);
        JdbcClient.StatementSpec statement =
                mock(JdbcClient.StatementSpec.class);
        @SuppressWarnings("unchecked")
        JdbcClient.MappedQuerySpec<Long> query =
                mock(JdbcClient.MappedQuerySpec.class);

        when(jdbc.sql(anyString())).thenReturn(statement);
        when(statement.param(anyString(), any())).thenReturn(statement);
        when(statement.query(Long.class)).thenReturn(query);
        when(query.single()).thenReturn(544L);

        long total = new AwardArchiveRepository(jdbc).countSearchAwardVersions(
                "%carbx%", "carbx", "", "", null, "all"
        );

        assertThat(total).isEqualTo(544L);
        assertThat(firstSql(jdbc))
                .contains("SELECT COUNT(*)")
                .contains("FROM archive.award_version av")
                .contains("CAST(:awardId AS BIGINT) IS NULL OR av.award_id = :awardId")
                .doesNotContain("WHERE av.is_primary_current = TRUE");
    }

    // --- searchAwardVersions/countSearchAwardVersions: general search
    // also matches award_number/workflow_document_number ------------------
    //
    // Fixture below is the real, live-verified regression case: Award
    // 200086-00001 (165 versions, current award_id 3047454, document
    // 879423) reported 0 results with the general search box alone, and
    // 0 results with the same value in both the general box and the
    // exact Award-number field, even though the Award genuinely exists
    // and the exact-match filter alone correctly returns all 165 rows -
    // confirmed live via a read-only ECS diagnostic against dev RDS
    // before this fix, and will be reconfirmed the same way after
    // deployment. These tests assert only the SQL shape this JdbcClient-
    // mock tier can verify; the literal row counts were and will be
    // proven against real Postgres data outside this test suite.

    @Test
    void searchAwardVersionsGeneralSearchMatchesExactAwardNumber() {
        JdbcClient jdbc = mock(JdbcClient.class);
        JdbcClient.StatementSpec statement =
                mock(JdbcClient.StatementSpec.class);
        @SuppressWarnings("unchecked")
        JdbcClient.MappedQuerySpec<AwardVersionSearchResultResponse> query =
                mock(JdbcClient.MappedQuerySpec.class);

        when(jdbc.sql(anyString())).thenReturn(statement);
        when(statement.param(anyString(), any())).thenReturn(statement);
        when(statement.query(AwardVersionSearchResultResponse.class))
                .thenReturn(query);
        when(query.list()).thenReturn(List.of());

        new AwardArchiveRepository(jdbc).searchAwardVersions(
                "%200086-00001%", "200086-00001", "", "", null, "all",
                "ORDER BY av.sequence_number DESC\n", 25, 0
        );

        assertThat(firstSql(jdbc))
                .contains("av.award_number ILIKE :pattern");
        verify(statement).param("pattern", "%200086-00001%");
        verify(statement).param("rawQuery", "200086-00001");
    }

    @Test
    void searchAwardVersionsGeneralSearchMatchesPartialAwardNumberViaIlike() {
        // The pattern itself (a substring wrapped in '%...%') is built by
        // AwardSearchPattern before reaching the repository - this test
        // only proves the repository's own SQL uses ILIKE (substring-
        // capable), not equality, for award_number in the free-text
        // clause, so a partial pattern like "%200086%" is structurally
        // able to match "200086-00001" through this same predicate.
        JdbcClient jdbc = mock(JdbcClient.class);
        JdbcClient.StatementSpec statement =
                mock(JdbcClient.StatementSpec.class);
        @SuppressWarnings("unchecked")
        JdbcClient.MappedQuerySpec<AwardVersionSearchResultResponse> query =
                mock(JdbcClient.MappedQuerySpec.class);

        when(jdbc.sql(anyString())).thenReturn(statement);
        when(statement.param(anyString(), any())).thenReturn(statement);
        when(statement.query(AwardVersionSearchResultResponse.class))
                .thenReturn(query);
        when(query.list()).thenReturn(List.of());

        new AwardArchiveRepository(jdbc).searchAwardVersions(
                "%200086%", "200086", "", "", null, "all",
                "ORDER BY av.sequence_number DESC\n", 25, 0
        );

        assertThat(firstSql(jdbc))
                .contains("av.award_number ILIKE :pattern")
                .doesNotContain("av.award_number = :pattern");
        verify(statement).param("pattern", "%200086%");
    }

    @Test
    void searchAwardVersionsGeneralSearchMatchesDocumentNumber() {
        JdbcClient jdbc = mock(JdbcClient.class);
        JdbcClient.StatementSpec statement =
                mock(JdbcClient.StatementSpec.class);
        @SuppressWarnings("unchecked")
        JdbcClient.MappedQuerySpec<AwardVersionSearchResultResponse> query =
                mock(JdbcClient.MappedQuerySpec.class);

        when(jdbc.sql(anyString())).thenReturn(statement);
        when(statement.param(anyString(), any())).thenReturn(statement);
        when(statement.query(AwardVersionSearchResultResponse.class))
                .thenReturn(query);
        when(query.list()).thenReturn(List.of());

        new AwardArchiveRepository(jdbc).searchAwardVersions(
                "%879423%", "879423", "", "", null, "all",
                "ORDER BY av.sequence_number DESC\n", 25, 0
        );

        assertThat(firstSql(jdbc))
                .contains("av.workflow_document_number ILIKE :pattern");
        verify(statement).param("pattern", "%879423%");
    }

    @Test
    void searchAwardVersionsSameValueInGeneralSearchAndExactAwardNumberBothContributeToTheMatch() {
        // The regression itself: awardNumber's own exact-match clause
        // and rawQuery's free-text OR-chain must both be present and
        // both able to independently satisfy their own AND-branch when
        // populated with the identical value - proving the fix doesn't
        // special-case "same value in both fields" by dropping either
        // clause, only widens what the free-text clause itself can match.
        JdbcClient jdbc = mock(JdbcClient.class);
        JdbcClient.StatementSpec statement =
                mock(JdbcClient.StatementSpec.class);
        @SuppressWarnings("unchecked")
        JdbcClient.MappedQuerySpec<AwardVersionSearchResultResponse> query =
                mock(JdbcClient.MappedQuerySpec.class);

        when(jdbc.sql(anyString())).thenReturn(statement);
        when(statement.param(anyString(), any())).thenReturn(statement);
        when(statement.query(AwardVersionSearchResultResponse.class))
                .thenReturn(query);
        when(query.list()).thenReturn(List.of());

        new AwardArchiveRepository(jdbc).searchAwardVersions(
                "%200086-00001%", "200086-00001", "200086-00001", "", null,
                "all", "ORDER BY av.sequence_number DESC\n", 25, 0
        );

        String sql = firstSql(jdbc);
        assertThat(sql)
                .contains("UPPER(av.award_number) = UPPER(:awardNumber)")
                .contains("av.award_number ILIKE :pattern");
        verify(statement).param("awardNumber", "200086-00001");
        verify(statement).param("rawQuery", "200086-00001");
        verify(statement).param("pattern", "%200086-00001%");
    }

    @Test
    void searchAwardVersionsSameValueInGeneralSearchAndExactDocumentNumberBothContributeToTheMatch() {
        JdbcClient jdbc = mock(JdbcClient.class);
        JdbcClient.StatementSpec statement =
                mock(JdbcClient.StatementSpec.class);
        @SuppressWarnings("unchecked")
        JdbcClient.MappedQuerySpec<AwardVersionSearchResultResponse> query =
                mock(JdbcClient.MappedQuerySpec.class);

        when(jdbc.sql(anyString())).thenReturn(statement);
        when(statement.param(anyString(), any())).thenReturn(statement);
        when(statement.query(AwardVersionSearchResultResponse.class))
                .thenReturn(query);
        when(query.list()).thenReturn(List.of());

        new AwardArchiveRepository(jdbc).searchAwardVersions(
                "%879423%", "879423", "", "879423", null,
                "all", "ORDER BY av.sequence_number DESC\n", 25, 0
        );

        String sql = firstSql(jdbc);
        assertThat(sql)
                .contains("UPPER(av.workflow_document_number) = UPPER(:documentNumber)")
                .contains("av.workflow_document_number ILIKE :pattern");
        verify(statement).param("documentNumber", "879423");
        verify(statement).param("rawQuery", "879423");
    }

    @Test
    void searchAwardVersionsUnrelatedGeneralSearchStillAndsWithExactFilterNotOr() {
        // An unrelated rawQuery combined with a valid exact filter must
        // remain a true negative - the fix widens what the free-text
        // clause can match, it never changes the top-level AND relating
        // the free-text clause to the exact-match clauses into an OR.
        JdbcClient jdbc = mock(JdbcClient.class);
        JdbcClient.StatementSpec statement =
                mock(JdbcClient.StatementSpec.class);
        @SuppressWarnings("unchecked")
        JdbcClient.MappedQuerySpec<AwardVersionSearchResultResponse> query =
                mock(JdbcClient.MappedQuerySpec.class);

        when(jdbc.sql(anyString())).thenReturn(statement);
        when(statement.param(anyString(), any())).thenReturn(statement);
        when(statement.query(AwardVersionSearchResultResponse.class))
                .thenReturn(query);
        when(query.list()).thenReturn(List.of());

        new AwardArchiveRepository(jdbc).searchAwardVersions(
                "%completely-unrelated-text%", "completely-unrelated-text",
                "200086-00001", "", null, "all",
                "ORDER BY av.sequence_number DESC\n", 25, 0
        );

        String sql = firstSql(jdbc);
        int exactClauseIndex = sql.indexOf(
                "UPPER(av.award_number) = UPPER(:awardNumber)"
        );
        int freeTextClauseIndex = sql.indexOf(":rawQuery = ''");
        int andBetween = sql.indexOf("AND", exactClauseIndex);

        assertThat(exactClauseIndex).isGreaterThan(-1);
        assertThat(freeTextClauseIndex).isGreaterThan(-1);
        // The AND joining the awardNumber sentinel to the next filter
        // group appears before the free-text group starts - i.e. the two
        // clauses are still ANDed, not OR-ed, regardless of the values
        // bound to them.
        assertThat(andBetween).isGreaterThan(-1).isLessThan(freeTextClauseIndex);
        assertThat(sql).doesNotContain(
                "UPPER(av.award_number) = UPPER(:awardNumber) OR"
        );
    }

    @Test
    void countSearchAwardVersionsAppliesTheSameGeneralSearchColumnsAsSearch() {
        // Count and list queries must never drift apart - both need the
        // exact same award_number/workflow_document_number ILIKE
        // predicates added by this fix, or pagination totals would
        // disagree with the actual result set.
        JdbcClient jdbc = mock(JdbcClient.class);
        JdbcClient.StatementSpec statement =
                mock(JdbcClient.StatementSpec.class);
        @SuppressWarnings("unchecked")
        JdbcClient.MappedQuerySpec<Long> query =
                mock(JdbcClient.MappedQuerySpec.class);

        when(jdbc.sql(anyString())).thenReturn(statement);
        when(statement.param(anyString(), any())).thenReturn(statement);
        when(statement.query(Long.class)).thenReturn(query);
        when(query.single()).thenReturn(165L);

        long total = new AwardArchiveRepository(jdbc).countSearchAwardVersions(
                "%200086-00001%", "200086-00001", "", "", null, "all"
        );

        assertThat(total).isEqualTo(165L);
        assertThat(firstSql(jdbc))
                .contains("av.award_number ILIKE :pattern")
                .contains("av.workflow_document_number ILIKE :pattern");
    }

    @Test
    void searchAwardVersionsExistingFreeTextColumnsStillPresent() {
        // Regression guard: title/sponsor/lead-unit/PI-name matching
        // (already correct before this fix) must survive unchanged
        // alongside the two new columns.
        JdbcClient jdbc = mock(JdbcClient.class);
        JdbcClient.StatementSpec statement =
                mock(JdbcClient.StatementSpec.class);
        @SuppressWarnings("unchecked")
        JdbcClient.MappedQuerySpec<AwardVersionSearchResultResponse> query =
                mock(JdbcClient.MappedQuerySpec.class);

        when(jdbc.sql(anyString())).thenReturn(statement);
        when(statement.param(anyString(), any())).thenReturn(statement);
        when(statement.query(AwardVersionSearchResultResponse.class))
                .thenReturn(query);
        when(query.list()).thenReturn(List.of());

        new AwardArchiveRepository(jdbc).searchAwardVersions(
                "%cancer%", "cancer", "", "", null, "all",
                "ORDER BY av.sequence_number DESC\n", 25, 0
        );

        assertThat(firstSql(jdbc))
                .contains("av.title ILIKE :pattern")
                .contains("av.sponsor_name ILIKE :pattern")
                .contains("av.lead_unit_name ILIKE :pattern")
                .contains("ap2.full_name ILIKE :pattern");
    }

    @Test
    void findSummaryByAwardIdIsNeverScopedToPrimaryCurrentAndExposesIt() {
        // Historical version support depends entirely on this endpoint
        // resolving an exact, possibly non-current award_id - it must
        // never silently redirect to the family's current version, and
        // the caller needs primaryCurrent to know which one it got.
        JdbcClient jdbc = mock(JdbcClient.class);
        JdbcClient.StatementSpec statement =
                mock(JdbcClient.StatementSpec.class);
        @SuppressWarnings("unchecked")
        JdbcClient.MappedQuerySpec<AwardSummaryResponse> query =
                mock(JdbcClient.MappedQuerySpec.class);

        when(jdbc.sql(anyString())).thenReturn(statement);
        when(statement.param("awardId", 3561589L)).thenReturn(statement);
        when(statement.query(AwardSummaryResponse.class)).thenReturn(query);
        when(query.optional()).thenReturn(Optional.empty());

        new AwardArchiveRepository(jdbc).findSummaryByAwardId(3561589L);

        assertThat(firstSql(jdbc))
                .contains("av.award_id = :awardId")
                .contains("av.is_primary_current AS primary_current")
                .contains("av.workflow_document_number AS document_number")
                .doesNotContain("is_primary_current = TRUE");
    }

    @Test
    void findExactWorkflowDocumentMatchQueriesAllVersionsNotJustCurrent() {
        JdbcClient jdbc = mock(JdbcClient.class);
        JdbcClient.StatementSpec statement =
                mock(JdbcClient.StatementSpec.class);
        @SuppressWarnings("unchecked")
        JdbcClient.MappedQuerySpec<AwardDocumentNumberMatchResponse> query =
                mock(JdbcClient.MappedQuerySpec.class);
        AwardDocumentNumberMatchResponse expected =
                new AwardDocumentNumberMatchResponse(
                        1135067L, "100567-00001", 6, "328797", "Award",
                        "Title", "Approved Award"
                );

        when(jdbc.sql(anyString())).thenReturn(statement);
        when(statement.param("rawQuery", "328797")).thenReturn(statement);
        when(statement.query(AwardDocumentNumberMatchResponse.class))
                .thenReturn(query);
        when(query.optional()).thenReturn(Optional.of(expected));

        Optional<AwardDocumentNumberMatchResponse> result =
                new AwardArchiveRepository(jdbc)
                        .findExactWorkflowDocumentMatch("328797");

        assertThat(result).contains(expected);
        assertThat(firstSql(jdbc))
                .contains("FROM archive.award_version")
                .contains("workflow_document_number = :rawQuery")
                .doesNotContain("is_primary_current")
                .contains("ORDER BY sequence_number DESC")
                .contains("LIMIT 1");
    }

    @Test
    void findExactWorkflowDocumentMatchReturnsEmptyForBlankQuery() {
        JdbcClient jdbc = mock(JdbcClient.class);

        Optional<AwardDocumentNumberMatchResponse> result =
                new AwardArchiveRepository(jdbc)
                        .findExactWorkflowDocumentMatch("  ");

        assertThat(result).isEmpty();
        org.mockito.Mockito.verifyNoInteractions(jdbc);
    }

    @Test
    void findUnitDetailsJoinsTheAwardsLeadUnitAgainstTheSharedUnitTable() {
        JdbcClient jdbc = mock(JdbcClient.class);
        JdbcClient.StatementSpec statement =
                mock(JdbcClient.StatementSpec.class);
        @SuppressWarnings("unchecked")
        JdbcClient.MappedQuerySpec<AwardUnitDetailsResponse> query =
                mock(JdbcClient.MappedQuerySpec.class);
        AwardUnitDetailsResponse expected = new AwardUnitDetailsResponse(
                "1203250000", "CAS SPACE PHYSICS", "1200000000",
                "COLLEGE OF ARTS & SCIENCES (CAS)", "1", true
        );

        when(jdbc.sql(anyString())).thenReturn(statement);
        when(statement.param("awardId", 985585L)).thenReturn(statement);
        when(statement.query(AwardUnitDetailsResponse.class)).thenReturn(query);
        when(query.optional()).thenReturn(Optional.of(expected));

        Optional<AwardUnitDetailsResponse> result =
                new AwardArchiveRepository(jdbc).findUnitDetails(985585L);

        assertThat(result).contains(expected);
        assertThat(firstSql(jdbc))
                .contains("FROM archive.award_version av")
                .contains("JOIN archive.unit u ON u.unit_number = av.lead_unit_number")
                .contains("LEFT JOIN archive.unit parent")
                .contains("av.award_id = :awardId");
    }

    @Test
    void findCentralAdministrationContactsFiltersToDefaultGroupFlagC() {
        JdbcClient jdbc = mock(JdbcClient.class);
        JdbcClient.StatementSpec statement =
                mock(JdbcClient.StatementSpec.class);
        @SuppressWarnings("unchecked")
        JdbcClient.MappedQuerySpec<AwardCentralAdministrationContactResponse> query =
                mock(JdbcClient.MappedQuerySpec.class);
        AwardCentralAdministrationContactResponse nancy =
                new AwardCentralAdministrationContactResponse(
                        "U44984650", "NANCY SCHINDELE", "PAFO Administrator",
                        "NANCYSCH@BU.EDU", "617-358-5117"
                );

        when(jdbc.sql(anyString())).thenReturn(statement);
        when(statement.param(anyString(), any())).thenReturn(statement);
        when(statement.query(AwardCentralAdministrationContactResponse.class))
                .thenReturn(query);
        when(query.list()).thenReturn(List.of(nancy));

        List<AwardCentralAdministrationContactResponse> result =
                new AwardArchiveRepository(jdbc)
                        .findCentralAdministrationContacts(985585L);

        assertThat(result).containsExactly(nancy);
        assertThat(firstSql(jdbc))
                .contains("JOIN archive.unit_administrator ua")
                .contains("ua.unit_number = av.lead_unit_number")
                .contains("JOIN archive.unit_administrator_type uat")
                .contains("uat.default_group_flag = 'C'")
                .contains("LEFT JOIN archive.person pe");
        verify(statement).param("awardId", 985585L);
    }

    @Test
    void findUnitContactsUsesRealArchivedAwardUnitContactData() {
        JdbcClient jdbc = mock(JdbcClient.class);
        JdbcClient.StatementSpec statement =
                mock(JdbcClient.StatementSpec.class);
        @SuppressWarnings("unchecked")
        JdbcClient.MappedQuerySpec<AwardUnitContactResponse> query =
                mock(JdbcClient.MappedQuerySpec.class);
        AwardUnitContactResponse erin = new AwardUnitContactResponse(
                "U17311007", "ERIN REYNOLDS", "Post-Award - Department Administrator",
                "1203250000", true, "EREYNOLD@BU.EDU", "617-358-0603"
        );

        when(jdbc.sql(anyString())).thenReturn(statement);
        when(statement.param(anyString(), any())).thenReturn(statement);
        when(statement.query(AwardUnitContactResponse.class)).thenReturn(query);
        when(query.list()).thenReturn(List.of(erin));

        List<AwardUnitContactResponse> result =
                new AwardArchiveRepository(jdbc).findUnitContacts(985585L);

        assertThat(result).containsExactly(erin);
        assertThat(firstSql(jdbc))
                .contains("FROM archive.award_unit_contact auc")
                .contains("JOIN archive.award_version av ON av.award_id = auc.award_id")
                .contains("LEFT JOIN archive.unit_administrator_type uat")
                .contains("LEFT JOIN archive.person pe ON pe.person_id = auc.person_id")
                .contains("auc.award_id = :awardId")
                // lead_unit binds to a primitive boolean field - a plain
                // "a = b" equality is SQL NULL (not false) whenever
                // unit_administrator_unit_number is NULL, which
                // SimplePropertyRowMapper cannot bind to a primitive and
                // throws "A null value cannot be assigned to a primitive
                // type" (reproduced live against Award 877025 - a real
                // contact row with a null unit number). The COALESCE(...,
                // FALSE) guard around that comparison must stay in place.
                .containsPattern(
                        "COALESCE\\(\\s*auc\\.unit_administrator_unit_number\\s*"
                                + "=\\s*av\\.lead_unit_number,\\s*FALSE\\s*\\)\\s*AS lead_unit"
                );
        verify(statement).param("awardId", 985585L);
    }

    @Test
    void findFundingSubawardRowsMatchesByAwardNumberFamilyWideAndResolvesTheActiveSubawardVersion() {
        JdbcClient jdbc = mock(JdbcClient.class);
        JdbcClient.StatementSpec statement =
                mock(JdbcClient.StatementSpec.class);
        @SuppressWarnings("unchecked")
        JdbcClient.MappedQuerySpec<AwardFundingSubawardResponse> query =
                mock(JdbcClient.MappedQuerySpec.class);

        when(jdbc.sql(anyString())).thenReturn(statement);
        when(statement.param(anyString(), any())).thenReturn(statement);
        when(statement.query(AwardFundingSubawardResponse.class))
                .thenReturn(query);
        when(query.list()).thenReturn(List.of());

        List<AwardFundingSubawardResponse> result =
                new AwardArchiveRepository(jdbc)
                        .findFundingSubawardRows("202505-00002");

        assertThat(result).isEmpty();
        assertThat(firstSql(jdbc))
                .contains("FROM archive.subaward_funding funding")
                .contains("JOIN archive.subaward linked_subaward")
                .contains("linked_subaward.subaward_id = funding.subaward_id")
                .contains("LEFT JOIN archive.subaward current_subaward")
                .contains(
                        "current_subaward.subaward_code = linked_subaward.subaward_code"
                )
                .contains("current_subaward.subaward_sequence_status = 'ACTIVE'")
                .contains("funding.award_number = :awardNumber")
                .contains("funding.subaward_id AS exact_linked_subaward_id")
                .contains(
                        "current_subaward.subaward_id AS navigable_current_subaward_id"
                );
        verify(statement).param("awardNumber", "202505-00002");
    }

    /**
     * Regression for a proven live defect (docs/architecture/
     * AWARD_FUNDING_PROPOSAL_UI_GAP_INVESTIGATION.md): Award
     * 202034-00001's only archive.award_funding_proposal row
     * (award_funding_proposal_id 663222, award_id 663209, proposal_id
     * 9199) was silently dropped by the UI-facing endpoint because
     * linked_proposal used to be an INNER JOIN and Institutional
     * Proposal 6327 has never been loaded into archive.proposal_version
     * (the Proposal domain is loaded incrementally, not all at once
     * like Award). The relationship row itself is the authoritative
     * source of whether a Funding Proposal exists - it must never
     * disappear just because Proposal detail hasn't been archived yet.
     */
    @Test
    void findFundingProposalRowsUsesALeftJoinToProposalVersionSoAnUnresolvedRelationshipIsNeverDropped() {
        JdbcClient jdbc = mock(JdbcClient.class);
        JdbcClient.StatementSpec statement =
                mock(JdbcClient.StatementSpec.class);
        @SuppressWarnings("unchecked")
        JdbcClient.MappedQuerySpec<AwardFundingProposalResponse> query =
                mock(JdbcClient.MappedQuerySpec.class);

        when(jdbc.sql(anyString())).thenReturn(statement);
        when(statement.param(anyString(), any())).thenReturn(statement);
        when(statement.query(AwardFundingProposalResponse.class))
                .thenReturn(query);
        when(query.list()).thenReturn(List.of());

        List<AwardFundingProposalResponse> result =
                new AwardArchiveRepository(jdbc)
                        .findFundingProposalRows("202034-00001");

        assertThat(result).isEmpty();
        assertThat(firstSql(jdbc))
                .contains("FROM archive.award_funding_proposal proposal")
                .contains(
                        "LEFT JOIN archive.proposal_version linked_proposal"
                )
                .contains(
                        "linked_proposal.proposal_id = proposal.proposal_id"
                )
                .contains("LEFT JOIN archive.proposal_version active_proposal")
                .contains("award.award_number = :awardNumber")
                .contains("proposal.award_funding_proposal_id")
                .contains("proposal.award_id")
                .contains("proposal.proposal_id AS exact_linked_proposal_id");
        verify(statement).param("awardNumber", "202034-00001");
    }

    @Test
    void findAssociatedNegotiationRowsMatchesByAwardNumberFamilyWide() {
        JdbcClient jdbc = mock(JdbcClient.class);
        JdbcClient.StatementSpec statement =
                mock(JdbcClient.StatementSpec.class);
        @SuppressWarnings("unchecked")
        JdbcClient.MappedQuerySpec<AwardAssociatedNegotiationResponse> query =
                mock(JdbcClient.MappedQuerySpec.class);

        when(jdbc.sql(anyString())).thenReturn(statement);
        when(statement.param(anyString(), any())).thenReturn(statement);
        when(statement.query(AwardAssociatedNegotiationResponse.class))
                .thenReturn(query);
        when(query.list()).thenReturn(List.of());

        List<AwardAssociatedNegotiationResponse> result =
                new AwardArchiveRepository(jdbc)
                        .findAssociatedNegotiationRows("202505-00002");

        assertThat(result).isEmpty();
        assertThat(firstSql(jdbc))
                .contains("FROM archive.negotiation")
                .contains("negotiation_association_type_code = 'AWD'")
                .contains("associated_document_id = :awardNumber")
                .doesNotContain("archive.award_version");
        verify(statement).param("awardNumber", "202505-00002");
    }

    @Test
    void findSponsorContactsResolvesThroughRolodex() {
        JdbcClient jdbc = mock(JdbcClient.class);
        JdbcClient.StatementSpec statement =
                mock(JdbcClient.StatementSpec.class);
        @SuppressWarnings("unchecked")
        JdbcClient.MappedQuerySpec<AwardSponsorContactResponse> query =
                mock(JdbcClient.MappedQuerySpec.class);

        when(jdbc.sql(anyString())).thenReturn(statement);
        when(statement.param(anyString(), any())).thenReturn(statement);
        when(statement.query(AwardSponsorContactResponse.class)).thenReturn(query);
        when(query.list()).thenReturn(List.of());

        List<AwardSponsorContactResponse> result =
                new AwardArchiveRepository(jdbc).findSponsorContacts(985585L);

        assertThat(result).isEmpty();
        assertThat(firstSql(jdbc))
                .contains("FROM archive.award_sponsor_contact asc_")
                .contains("LEFT JOIN archive.rolodex r ON r.rolodex_id = asc_.rolodex_id")
                .contains("asc_.award_id = :awardId");
        verify(statement).param("awardId", 985585L);
    }

    @Test
    void findExplorerAwardByNumberFiltersToPrimaryCurrent() {
        JdbcClient jdbc = mock(JdbcClient.class);
        JdbcClient.StatementSpec statement =
                mock(JdbcClient.StatementSpec.class);
        @SuppressWarnings("unchecked")
        JdbcClient.MappedQuerySpec<ExplorerAwardResponse> query =
                mock(JdbcClient.MappedQuerySpec.class);
        ExplorerAwardResponse expected = new ExplorerAwardResponse(
                985585L, "100012-00002", 7, "Title", "Closed",
                "JOHN T CLARKE", "300940", null, "1203250000",
                "CAS SPACE PHYSICS", true
        );

        when(jdbc.sql(anyString())).thenReturn(statement);
        when(statement.param("awardNumber", "100012-00002"))
                .thenReturn(statement);
        when(statement.query(ExplorerAwardResponse.class)).thenReturn(query);
        when(query.optional()).thenReturn(Optional.of(expected));

        Optional<ExplorerAwardResponse> result =
                new AwardArchiveRepository(jdbc)
                        .findExplorerAwardByNumber("100012-00002");

        assertThat(result).contains(expected);
        assertThat(firstSql(jdbc))
                .contains("FROM archive.award_version av")
                .contains("LEFT JOIN LATERAL")
                .contains("av.award_number = :awardNumber")
                .contains("av.is_primary_current = TRUE");
    }

    @Test
    void findExplorerAwardVersionByIdDoesNotFilterToPrimaryCurrent() {
        JdbcClient jdbc = mock(JdbcClient.class);
        JdbcClient.StatementSpec statement =
                mock(JdbcClient.StatementSpec.class);
        @SuppressWarnings("unchecked")
        JdbcClient.MappedQuerySpec<ExplorerAwardResponse> query =
                mock(JdbcClient.MappedQuerySpec.class);

        when(jdbc.sql(anyString())).thenReturn(statement);
        when(statement.param("awardId", 511L)).thenReturn(statement);
        when(statement.query(ExplorerAwardResponse.class)).thenReturn(query);
        when(query.optional()).thenReturn(Optional.empty());

        new AwardArchiveRepository(jdbc).findExplorerAwardVersionById(511L);

        assertThat(firstSql(jdbc))
                .contains("av.award_id = :awardId")
                .doesNotContain("is_primary_current = TRUE");
    }

    @Test
    void findUnitByNumberJoinsParentUnit() {
        JdbcClient jdbc = mock(JdbcClient.class);
        JdbcClient.StatementSpec statement =
                mock(JdbcClient.StatementSpec.class);
        @SuppressWarnings("unchecked")
        JdbcClient.MappedQuerySpec<ExplorerUnitRow> query =
                mock(JdbcClient.MappedQuerySpec.class);
        ExplorerUnitRow expected = new ExplorerUnitRow(
                "1203250000", "CAS SPACE PHYSICS", "1200000000",
                "COLLEGE OF ARTS & SCIENCES (CAS)", "1"
        );

        when(jdbc.sql(anyString())).thenReturn(statement);
        when(statement.param("unitNumber", "1203250000")).thenReturn(statement);
        when(statement.query(ExplorerUnitRow.class)).thenReturn(query);
        when(query.optional()).thenReturn(Optional.of(expected));

        Optional<ExplorerUnitRow> result =
                new AwardArchiveRepository(jdbc).findUnitByNumber("1203250000");

        assertThat(result).contains(expected);
        assertThat(firstSql(jdbc))
                .contains("FROM archive.unit u")
                .contains("LEFT JOIN archive.unit parent")
                .contains("u.unit_number = :unitNumber");
    }

    @Test
    void findUnitAdministratorsByUnitNumberIncludesEveryGroupNotOnlyC() {
        JdbcClient jdbc = mock(JdbcClient.class);
        JdbcClient.StatementSpec statement =
                mock(JdbcClient.StatementSpec.class);
        @SuppressWarnings("unchecked")
        JdbcClient.MappedQuerySpec<ExplorerUnitAdministratorResponse> query =
                mock(JdbcClient.MappedQuerySpec.class);

        when(jdbc.sql(anyString())).thenReturn(statement);
        when(statement.param(anyString(), any())).thenReturn(statement);
        when(statement.query(ExplorerUnitAdministratorResponse.class))
                .thenReturn(query);
        when(query.list()).thenReturn(List.of());

        new AwardArchiveRepository(jdbc)
                .findUnitAdministratorsByUnitNumber("1203250000");

        assertThat(firstSql(jdbc))
                .contains("FROM archive.unit_administrator ua")
                .contains("ua.unit_number = :unitNumber")
                .doesNotContain("default_group_flag = 'C'");
        verify(statement).param("unitNumber", "1203250000");
    }

    @Test
    void findExplorerPersonByIdQueriesArchivePerson() {
        JdbcClient jdbc = mock(JdbcClient.class);
        JdbcClient.StatementSpec statement =
                mock(JdbcClient.StatementSpec.class);
        @SuppressWarnings("unchecked")
        JdbcClient.MappedQuerySpec<ExplorerPersonResponse> query =
                mock(JdbcClient.MappedQuerySpec.class);

        when(jdbc.sql(anyString())).thenReturn(statement);
        when(statement.param("personId", "U44984650")).thenReturn(statement);
        when(statement.query(ExplorerPersonResponse.class)).thenReturn(query);
        when(query.optional()).thenReturn(Optional.empty());

        new AwardArchiveRepository(jdbc).findExplorerPersonById("U44984650");

        assertThat(firstSql(jdbc))
                .contains("FROM archive.person")
                .contains("person_id = :personId");
    }

    @Test
    void findExplorerRolodexByIdQueriesArchiveRolodex() {
        JdbcClient jdbc = mock(JdbcClient.class);
        JdbcClient.StatementSpec statement =
                mock(JdbcClient.StatementSpec.class);
        @SuppressWarnings("unchecked")
        JdbcClient.MappedQuerySpec<ExplorerRolodexResponse> query =
                mock(JdbcClient.MappedQuerySpec.class);

        when(jdbc.sql(anyString())).thenReturn(statement);
        when(statement.param("rolodexId", 501L)).thenReturn(statement);
        when(statement.query(ExplorerRolodexResponse.class)).thenReturn(query);
        when(query.optional()).thenReturn(Optional.empty());

        new AwardArchiveRepository(jdbc).findExplorerRolodexById(501L);

        assertThat(firstSql(jdbc))
                .contains("FROM archive.rolodex")
                .contains("rolodex_id = :rolodexId");
    }

    @Test
    void findAwardsBySponsorCodeQueriesAwardVersionNotRolodex() {
        JdbcClient jdbc = mock(JdbcClient.class);
        JdbcClient.StatementSpec statement =
                mock(JdbcClient.StatementSpec.class);
        @SuppressWarnings("unchecked")
        JdbcClient.MappedQuerySpec<ExplorerAwardResponse> query =
                mock(JdbcClient.MappedQuerySpec.class);

        when(jdbc.sql(anyString())).thenReturn(statement);
        when(statement.param("sponsorCode", "NIH")).thenReturn(statement);
        when(statement.query(ExplorerAwardResponse.class)).thenReturn(query);
        when(query.list()).thenReturn(List.of());

        new AwardArchiveRepository(jdbc).findAwardsBySponsorCode("NIH");

        // archive.rolodex has no sponsor_code column at all (confirmed
        // against information_schema/V056) - sponsor_code/sponsor_name
        // are denormalized directly onto archive.award_version instead.
        assertThat(firstSql(jdbc))
                .contains("FROM archive.award_version")
                .contains("av.sponsor_code = :sponsorCode")
                .doesNotContain("archive.rolodex");
    }

    @Test
    void findProposalDiscoveryRowsScopesToActiveVersionAndNeverDuplicatesViaAmountHistory() {
        JdbcClient jdbc = mock(JdbcClient.class);
        JdbcClient.StatementSpec statement =
                mock(JdbcClient.StatementSpec.class);
        @SuppressWarnings("unchecked")
        JdbcClient.MappedQuerySpec<edu.bu.archive.adapter.in.web.dto.explorer.ExplorerProposalDiscoveryResponse> query =
                mock(JdbcClient.MappedQuerySpec.class);

        when(jdbc.sql(anyString())).thenReturn(statement);
        when(statement.param(anyString(), any())).thenReturn(statement);
        when(statement.query(
                edu.bu.archive.adapter.in.web.dto.explorer.ExplorerProposalDiscoveryResponse.class
        )).thenReturn(query);
        when(query.list()).thenReturn(List.of());

        new AwardArchiveRepository(jdbc).findProposalDiscoveryRows(
                null, null, null, null, null, null, null, null, null, null,
                null, null, 50, 0
        );

        String sql = firstSql(jdbc);
        assertThat(sql)
                .contains("FROM archive.proposal_version pv")
                .contains("pv.proposal_sequence_status = 'ACTIVE'")
                // attachment count via a LATERAL COUNT(*), never a join
                // that could multiply the Proposal's own row
                .contains("LEFT JOIN LATERAL")
                .contains("SELECT COUNT(*) AS attachment_count")
                // PI via a LATERAL pick of archive.proposal_person,
                // never a join that could fan out the Proposal's row
                .contains("FROM archive.proposal_person pp")
                .contains("pp.contact_role_code = 'PI'")
                // current amount is a single LATERAL-picked row (ORDER
                // BY ... LIMIT 1) off award_amount_info, never an
                // unbounded join across that table's full history
                .contains("FROM archive.award_amount_info amount")
                .contains("ORDER BY amount.award_amount_info_id DESC")
                .contains("LIMIT 1")
                .contains("current_award.is_primary_current = TRUE")
                .doesNotContain("pgvector")
                .doesNotContain("embedding")
                .doesNotContain("<->");
    }

    /** See searchAwardsOrdersCurrentAmountByAwardAmountInfoIdOnly for the
     * full rationale - same proven defect, same fix. */
    @Test
    void findCurrentAmountsOrdersByAwardAmountInfoIdOnly() {
        JdbcClient jdbc = mock(JdbcClient.class);
        JdbcClient.StatementSpec statement =
                mock(JdbcClient.StatementSpec.class);
        @SuppressWarnings("unchecked")
        JdbcClient.MappedQuerySpec<
                edu.bu.archive.adapter.in.web.dto.award.AwardAmountResponse
                > query = mock(JdbcClient.MappedQuerySpec.class);

        when(jdbc.sql(anyString())).thenReturn(statement);
        when(statement.param(anyString(), any())).thenReturn(statement);
        when(statement.query(
                edu.bu.archive.adapter.in.web.dto.award
                        .AwardAmountResponse.class
        )).thenReturn(query);
        when(query.list()).thenReturn(List.of());

        new AwardArchiveRepository(jdbc)
                .findCurrentAmounts("204713-00133");

        assertThat(firstSql(jdbc))
                .contains("FROM archive.award_amount_info amount")
                .contains("amount.award_amount_info_id DESC")
                .doesNotContain("source_version_number DESC");
    }

    @Test
    void findCustomDataResolvesTheLookupAndScopesByExactAwardId() {
        JdbcClient jdbc = mock(JdbcClient.class);
        JdbcClient.StatementSpec statement =
                mock(JdbcClient.StatementSpec.class);
        @SuppressWarnings("unchecked")
        JdbcClient.MappedQuerySpec<AwardCustomDataResponse> query =
                mock(JdbcClient.MappedQuerySpec.class);

        when(jdbc.sql(anyString())).thenReturn(statement);
        when(statement.param(anyString(), any())).thenReturn(statement);
        when(statement.query(AwardCustomDataResponse.class))
                .thenReturn(query);
        when(query.list()).thenReturn(List.of());

        new AwardArchiveRepository(jdbc).findCustomData(3160098L);

        // custom_attribute_id has no foreign key (V038/V064) - this
        // must be a LEFT JOIN, never an inner join, so a row with no
        // matching lookup still comes back. Scoped by the exact
        // award_id (version-scoped), never award_number (which would
        // fan out across sibling versions and silently combine their
        // data) - and never joined against award_sponsor_term/
        // award_report_term, which is a separate Award section.
        assertThat(firstSql(jdbc))
                .contains("FROM archive.award_custom_data acd")
                .contains("LEFT JOIN archive.custom_attribute ca")
                .contains("acd.award_id = :awardId")
                .doesNotContain("award_sponsor_term")
                .doesNotContain("award_report_term");
    }

    private String firstSql(JdbcClient jdbc) {
        return org.mockito.Mockito
                .mockingDetails(jdbc)
                .getInvocations()
                .stream()
                .filter(invocation ->
                        invocation.getMethod().getName().equals("sql")
                )
                .map(invocation -> (String) invocation.getArgument(0))
                .findFirst()
                .orElseThrow()
                .replaceAll("\\s+", " ");
    }
}
