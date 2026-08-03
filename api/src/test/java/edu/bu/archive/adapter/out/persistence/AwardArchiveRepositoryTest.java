package edu.bu.archive.adapter.out.persistence;

import edu.bu.archive.adapter.in.web.dto.award.AwardDocumentNumberMatchResponse;
import edu.bu.archive.adapter.in.web.dto.award.AwardHierarchyEdgeRow;
import edu.bu.archive.adapter.in.web.dto.award.AwardSearchResultResponse;
import edu.bu.archive.adapter.in.web.dto.award.AwardSummaryCardRow;
import edu.bu.archive.adapter.in.web.dto.award.AwardSummaryResponse;
import edu.bu.archive.adapter.in.web.dto.award.AwardVersionSummaryResponse;

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
                null, null
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
