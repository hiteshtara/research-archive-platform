package edu.bu.archive.adapter.out.persistence;

import edu.bu.archive.adapter.in.web.dto.award.TimeAndMoneyActionResponse;
import edu.bu.archive.adapter.in.web.dto.award.TimeAndMoneyDocumentResponse;
import edu.bu.archive.adapter.in.web.dto.award.TimeAndMoneyHistoryEntryResponse;
import edu.bu.archive.adapter.in.web.dto.award.TimeAndMoneySummaryResponse;
import edu.bu.archive.adapter.in.web.dto.award.TimeAndMoneyTransactionDetailResponse;
import edu.bu.archive.adapter.in.web.dto.award.TimeAndMoneyTransactionHeaderRow;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/*
 * Repository-layer tests for AwardArchiveRepository's Time and Money
 * methods (see docs/architecture/AWARD_TIME_AND_MONEY_DESIGN.md), kept
 * as their own file given the size of this feature area (mirrors
 * ExplorerContractTest's/ExplorerServiceTest's own-file precedent from
 * the Archive Explorer bundle).
 */
class TimeAndMoneyRepositoryTest {

    @Test
    void findTimeAndMoneySummaryScopesTotalsToExactAwardIdButLastActionToTheWholeFamily() {
        JdbcClient jdbc = mock(JdbcClient.class);
        JdbcClient.StatementSpec statement =
                mock(JdbcClient.StatementSpec.class);
        @SuppressWarnings("unchecked")
        JdbcClient.MappedQuerySpec<TimeAndMoneySummaryResponse> query =
                mock(JdbcClient.MappedQuerySpec.class);

        when(jdbc.sql(anyString())).thenReturn(statement);
        when(statement.param("awardId", 3L)).thenReturn(statement);
        when(statement.query(TimeAndMoneySummaryResponse.class))
                .thenReturn(query);
        when(query.optional()).thenReturn(Optional.empty());

        new AwardArchiveRepository(jdbc).findTimeAndMoneySummary(3L);

        String sql = firstSql(jdbc);
        assertThat(sql)
                .contains("FROM archive.award_amount_info amount")
                .contains("INNER JOIN archive.award_version av ON av.award_id = amount.award_id")
                // totals stay scoped to this exact award_id
                .contains("WHERE amount.award_id = :awardId")
                .contains("ORDER BY amount.award_amount_info_id DESC")
                .contains("LIMIT 1")
                // last action/count scope to the whole award_number
                // family via award_amount_transaction, NOT to this
                // exact award_id's own award_amount_info rows - the
                // bug this test guards against (empty summaries on
                // most ordinary Awards' current version).
                .contains("FROM archive.award_amount_transaction aat")
                .contains("aat.award_number = amount.award_number")
                .doesNotContain("tnm.transaction_id IS NOT NULL");
        // exactly two family-scoped subqueries (count + last action),
        // neither filtered down to this one award_id.
        assertThat(sql.split("aat\\.award_number = amount\\.award_number", -1))
                .hasSize(3);
        verify(statement).param("awardId", 3L);
    }

    @Test
    void countTimeAndMoneyActionsQueriesAwardAmountTransaction() {
        JdbcClient jdbc = mock(JdbcClient.class);
        JdbcClient.StatementSpec statement =
                mock(JdbcClient.StatementSpec.class);
        @SuppressWarnings("unchecked")
        JdbcClient.MappedQuerySpec<Long> query =
                mock(JdbcClient.MappedQuerySpec.class);

        when(jdbc.sql(anyString())).thenReturn(statement);
        when(statement.param(anyString(), any())).thenReturn(statement);
        when(statement.query(Long.class)).thenReturn(query);
        when(query.single()).thenReturn(3L);

        long count = new AwardArchiveRepository(jdbc)
                .countTimeAndMoneyActions("100004-00003");

        assertThat(count).isEqualTo(3L);
        assertThat(firstSql(jdbc))
                .contains("FROM archive.award_amount_transaction")
                .contains("award_number = :awardNumber");
    }

    @Test
    void countTimeAndMoneyActionsTreatsANullCountAsZero() {
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

        long count = new AwardArchiveRepository(jdbc)
                .countTimeAndMoneyActions("100004-00003");

        assertThat(count).isZero();
    }

    @Test
    void findTimeAndMoneyActionsJoinsTimeAndMoneyDocumentAndOrdersNewestFirst() {
        JdbcClient jdbc = mock(JdbcClient.class);
        JdbcClient.StatementSpec statement =
                mock(JdbcClient.StatementSpec.class);
        @SuppressWarnings("unchecked")
        JdbcClient.MappedQuerySpec<TimeAndMoneyActionResponse> query =
                mock(JdbcClient.MappedQuerySpec.class);

        when(jdbc.sql(anyString())).thenReturn(statement);
        when(statement.param(anyString(), any())).thenReturn(statement);
        when(statement.query(TimeAndMoneyActionResponse.class))
                .thenReturn(query);
        when(query.list()).thenReturn(List.of());

        new AwardArchiveRepository(jdbc)
                .findTimeAndMoneyActions("100004-00003", 50, 0);

        assertThat(firstSql(jdbc))
                .contains("FROM archive.award_amount_transaction aat")
                .contains("LEFT JOIN archive.time_and_money_document tmd")
                .contains("tmd.document_number = aat.document_number")
                .contains("aat.document_number AS time_and_money_document_number")
                .contains("aat.award_number = :awardNumber")
                .contains("ORDER BY")
                .contains("aat.notice_date DESC NULLS LAST");
        verify(statement).param("awardNumber", "100004-00003");
        verify(statement).param("limit", 50);
        verify(statement).param("offset", 0);
    }

    @Test
    void findTimeAndMoneyHistoryComputesTimeAndMoneyCreatedInSql() {
        JdbcClient jdbc = mock(JdbcClient.class);
        JdbcClient.StatementSpec statement =
                mock(JdbcClient.StatementSpec.class);
        @SuppressWarnings("unchecked")
        JdbcClient.MappedQuerySpec<TimeAndMoneyHistoryEntryResponse> query =
                mock(JdbcClient.MappedQuerySpec.class);

        when(jdbc.sql(anyString())).thenReturn(statement);
        when(statement.param(anyString(), any())).thenReturn(statement);
        when(statement.query(TimeAndMoneyHistoryEntryResponse.class))
                .thenReturn(query);
        when(query.list()).thenReturn(List.of());

        new AwardArchiveRepository(jdbc)
                .findTimeAndMoneyHistory("100004-00003", 50, 0);

        assertThat(firstSql(jdbc))
                .contains("FROM archive.award_amount_info amount")
                .contains("amount.transaction_id AS pending_transaction_id")
                .contains("amount.tnm_document_number AS time_and_money_document_number")
                .contains("amount.originating_award_version")
                // the centralized, SQL-level derivation of
                // timeAndMoneyCreated - never left to Java/React.
                .contains("amount.transaction_id IS NOT NULL")
                .contains("amount.tnm_document_number IS NOT NULL")
                .contains("AS time_and_money_created")
                .contains("av.award_number = :awardNumber")
                .contains("ORDER BY")
                .contains("av.sequence_number DESC");
        verify(statement).param("awardNumber", "100004-00003");
        verify(statement).param("limit", 50);
        verify(statement).param("offset", 0);
    }

    @Test
    void findTimeAndMoneyTransactionHeaderJoinsPendingTransactionExtension() {
        JdbcClient jdbc = mock(JdbcClient.class);
        JdbcClient.StatementSpec statement =
                mock(JdbcClient.StatementSpec.class);
        @SuppressWarnings("unchecked")
        JdbcClient.MappedQuerySpec<TimeAndMoneyTransactionHeaderRow> query =
                mock(JdbcClient.MappedQuerySpec.class);

        when(jdbc.sql(anyString())).thenReturn(statement);
        when(statement.param("pendingTransactionId", 555L))
                .thenReturn(statement);
        when(statement.query(TimeAndMoneyTransactionHeaderRow.class))
                .thenReturn(query);
        when(query.optional()).thenReturn(Optional.empty());

        new AwardArchiveRepository(jdbc)
                .findTimeAndMoneyTransactionHeader(555L);

        assertThat(firstSql(jdbc))
                .contains("FROM archive.pending_transaction pt")
                .contains("LEFT JOIN archive.pending_transaction_extension pte")
                .contains("pte.transaction_id = pt.transaction_id")
                .contains("pt.transaction_id = :pendingTransactionId")
                .contains("pt.document_number AS time_and_money_document_number")
                .contains("pte.budget_period AS fanda_distribution_period");
        verify(statement).param("pendingTransactionId", 555L);
    }

    @Test
    void findTimeAndMoneyTransactionDetailsQueriesTransactionDetailOrderedById() {
        JdbcClient jdbc = mock(JdbcClient.class);
        JdbcClient.StatementSpec statement =
                mock(JdbcClient.StatementSpec.class);
        @SuppressWarnings("unchecked")
        JdbcClient.MappedQuerySpec<TimeAndMoneyTransactionDetailResponse> query =
                mock(JdbcClient.MappedQuerySpec.class);

        when(jdbc.sql(anyString())).thenReturn(statement);
        when(statement.param("pendingTransactionId", 555L))
                .thenReturn(statement);
        when(statement.query(TimeAndMoneyTransactionDetailResponse.class))
                .thenReturn(query);
        when(query.list()).thenReturn(List.of());

        new AwardArchiveRepository(jdbc)
                .findTimeAndMoneyTransactionDetails(555L);

        assertThat(firstSql(jdbc))
                .contains("FROM archive.transaction_detail td")
                .contains("td.time_and_money_document_number")
                .contains("td.transaction_id = :pendingTransactionId")
                .contains("ORDER BY td.transaction_detail_id");
        verify(statement).param("pendingTransactionId", 555L);
    }

    @Test
    void findTimeAndMoneyDocumentQueriesTimeAndMoneyDocument() {
        JdbcClient jdbc = mock(JdbcClient.class);
        JdbcClient.StatementSpec statement =
                mock(JdbcClient.StatementSpec.class);
        @SuppressWarnings("unchecked")
        JdbcClient.MappedQuerySpec<TimeAndMoneyDocumentResponse> query =
                mock(JdbcClient.MappedQuerySpec.class);

        when(jdbc.sql(anyString())).thenReturn(statement);
        when(statement.param("documentNumber", "281518"))
                .thenReturn(statement);
        when(statement.query(TimeAndMoneyDocumentResponse.class))
                .thenReturn(query);
        when(query.optional()).thenReturn(Optional.empty());

        new AwardArchiveRepository(jdbc).findTimeAndMoneyDocument("281518");

        assertThat(firstSql(jdbc))
                .contains("FROM archive.time_and_money_document")
                .contains("document_number AS time_and_money_document_number")
                .contains("document_number = :documentNumber");
        verify(statement).param("documentNumber", "281518");
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
