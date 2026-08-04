package edu.bu.archive.adapter.out.persistence;

import edu.bu.archive.adapter.in.web.dto.award.AwardBudgetLineItemResponse;
import edu.bu.archive.adapter.in.web.dto.award.AwardBudgetPeriodResponse;
import edu.bu.archive.adapter.in.web.dto.award.AwardBudgetPersonnelResponse;
import edu.bu.archive.adapter.in.web.dto.award.AwardBudgetRow;
import edu.bu.archive.adapter.in.web.dto.award.AwardFamilyPositionRow;

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
 * Repository-layer tests for AwardArchiveRepository's Budget methods -
 * see docs/kuali-business-rules/Budget.md. Kept as its own file, the
 * same precedent already used for TimeAndMoneyRepositoryTest.
 */
class AwardBudgetRepositoryTest {

    @Test
    void findFamilyPositionForIdQueriesAwardVersion() {
        JdbcClient jdbc = mock(JdbcClient.class);
        JdbcClient.StatementSpec statement =
                mock(JdbcClient.StatementSpec.class);
        @SuppressWarnings("unchecked")
        JdbcClient.MappedQuerySpec<AwardFamilyPositionRow> query =
                mock(JdbcClient.MappedQuerySpec.class);

        when(jdbc.sql(anyString())).thenReturn(statement);
        when(statement.param("awardId", 3L)).thenReturn(statement);
        when(statement.query(AwardFamilyPositionRow.class)).thenReturn(query);
        when(query.optional()).thenReturn(Optional.empty());

        new AwardArchiveRepository(jdbc).findFamilyPositionForId(3L);

        assertThat(firstSql(jdbc))
                .contains("SELECT award_number, sequence_number")
                .contains("FROM archive.award_version")
                .contains("WHERE award_id = :awardId");
        verify(statement).param("awardId", 3L);
    }

    @Test
    void findBudgetsInScopeQueriesTheWholeFamilyBoundedBySequence() {
        JdbcClient jdbc = mock(JdbcClient.class);
        JdbcClient.StatementSpec statement =
                mock(JdbcClient.StatementSpec.class);
        @SuppressWarnings("unchecked")
        JdbcClient.MappedQuerySpec<AwardBudgetRow> query =
                mock(JdbcClient.MappedQuerySpec.class);

        when(jdbc.sql(anyString())).thenReturn(statement);
        when(statement.param(anyString(), any())).thenReturn(statement);
        when(statement.query(AwardBudgetRow.class)).thenReturn(query);
        when(query.list()).thenReturn(List.of());

        new AwardArchiveRepository(jdbc)
                .findBudgetsInScope("103692-00002", 46);

        String sql = firstSql(jdbc);
        assertThat(sql)
                .contains("FROM archive.award_budget ab")
                .contains("JOIN archive.award_version av ON av.award_id = ab.award_id")
                // family-wide, NOT WHERE ab.award_id = :awardId - the
                // exact-awardId assumption this feature's own
                // investigation disproved.
                .contains("WHERE av.award_number = :awardNumber")
                .contains("av.sequence_number <= :viewedSequenceNumber")
                .doesNotContain("ab.award_id = :awardId")
                // Budget semantic fix (docs/kuali-business-rules/Budget.md):
                // these are selected verbatim, never recomputed in SQL.
                .contains("ab.obligated_total AS award_budget_total_cost_limit")
                .contains("ab.total_cost_limit AS budget_change_total_cost_limit")
                .contains("ORDER BY ab.budget_version_number DESC");
        verify(statement).param("awardNumber", "103692-00002");
        verify(statement).param("viewedSequenceNumber", 46);
    }

    @Test
    void findBudgetPeriodsScopesByBudgetId() {
        JdbcClient jdbc = mock(JdbcClient.class);
        JdbcClient.StatementSpec statement =
                mock(JdbcClient.StatementSpec.class);
        @SuppressWarnings("unchecked")
        JdbcClient.MappedQuerySpec<AwardBudgetPeriodResponse> query =
                mock(JdbcClient.MappedQuerySpec.class);

        when(jdbc.sql(anyString())).thenReturn(statement);
        when(statement.param("budgetId", 213642L)).thenReturn(statement);
        when(statement.query(AwardBudgetPeriodResponse.class)).thenReturn(query);
        when(query.list()).thenReturn(List.of());

        new AwardArchiveRepository(jdbc).findBudgetPeriods(213642L);

        assertThat(firstSql(jdbc))
                .contains("FROM archive.award_budget_period")
                .contains("WHERE budget_id = :budgetId");
        verify(statement).param("budgetId", 213642L);
    }

    @Test
    void countBudgetLineItemsJoinsThroughPeriodToScopeByBudgetId() {
        JdbcClient jdbc = mock(JdbcClient.class);
        JdbcClient.StatementSpec statement =
                mock(JdbcClient.StatementSpec.class);
        @SuppressWarnings("unchecked")
        JdbcClient.MappedQuerySpec<Long> query =
                mock(JdbcClient.MappedQuerySpec.class);

        when(jdbc.sql(anyString())).thenReturn(statement);
        when(statement.param(anyString(), any())).thenReturn(statement);
        when(statement.query(Long.class)).thenReturn(query);
        when(query.single()).thenReturn(5L);

        long count = new AwardArchiveRepository(jdbc)
                .countBudgetLineItems(213642L);

        assertThat(count).isEqualTo(5L);
        assertThat(firstSql(jdbc))
                .contains("FROM archive.award_budget_line_item bli")
                .contains("JOIN archive.award_budget_period bp")
                .contains("WHERE bp.budget_id = :budgetId");
    }

    @Test
    void countBudgetLineItemsTreatsANullCountAsZero() {
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
                .countBudgetLineItems(213642L);

        assertThat(count).isZero();
    }

    @Test
    void findBudgetLineItemsJoinsThroughPeriodAndOrdersByPeriodThenLineItem() {
        JdbcClient jdbc = mock(JdbcClient.class);
        JdbcClient.StatementSpec statement =
                mock(JdbcClient.StatementSpec.class);
        @SuppressWarnings("unchecked")
        JdbcClient.MappedQuerySpec<AwardBudgetLineItemResponse> query =
                mock(JdbcClient.MappedQuerySpec.class);

        when(jdbc.sql(anyString())).thenReturn(statement);
        when(statement.param(anyString(), any())).thenReturn(statement);
        when(statement.query(AwardBudgetLineItemResponse.class))
                .thenReturn(query);
        when(query.list()).thenReturn(List.of());

        new AwardArchiveRepository(jdbc)
                .findBudgetLineItems(213642L, 50, 0);

        assertThat(firstSql(jdbc))
                .contains("FROM archive.award_budget_line_item bli")
                .contains("JOIN archive.award_budget_period bp")
                .contains("WHERE bp.budget_id = :budgetId")
                .contains("bli.line_item_description AS description")
                .contains("ORDER BY bp.budget_period, bli.line_item_number");
        verify(statement).param("budgetId", 213642L);
        verify(statement).param("limit", 50);
        verify(statement).param("offset", 0);
    }

    @Test
    void findBudgetPersonnelJoinsPersonRosterAndSumsCalculatedAmounts() {
        JdbcClient jdbc = mock(JdbcClient.class);
        JdbcClient.StatementSpec statement =
                mock(JdbcClient.StatementSpec.class);
        @SuppressWarnings("unchecked")
        JdbcClient.MappedQuerySpec<AwardBudgetPersonnelResponse> query =
                mock(JdbcClient.MappedQuerySpec.class);

        when(jdbc.sql(anyString())).thenReturn(statement);
        when(statement.param(anyString(), any())).thenReturn(statement);
        when(statement.query(AwardBudgetPersonnelResponse.class))
                .thenReturn(query);
        when(query.list()).thenReturn(List.of());

        new AwardArchiveRepository(jdbc)
                .findBudgetPersonnel(213642L, 50, 0);

        String sql = firstSql(jdbc);
        assertThat(sql)
                .contains("FROM archive.award_budget_personnel_detail bpd")
                .contains("LEFT JOIN archive.award_budget_person abp")
                .contains("abp.budget_id = bpd.budget_id")
                .contains("abp.person_sequence_number = bpd.person_sequence_number")
                // calculated_salary sums real stored rows, never a
                // fabricated single-rate formula.
                .contains("SUM(bpca.calculated_cost) AS calculated_salary")
                .contains("archive.award_budget_personnel_calculated_amount bpca")
                .contains("WHERE bpd.budget_id = :budgetId");
        verify(statement).param("budgetId", 213642L);
        verify(statement).param("limit", 50);
        verify(statement).param("offset", 0);
    }

    @Test
    void countBudgetPersonnelQueriesPersonnelDetail() {
        JdbcClient jdbc = mock(JdbcClient.class);
        JdbcClient.StatementSpec statement =
                mock(JdbcClient.StatementSpec.class);
        @SuppressWarnings("unchecked")
        JdbcClient.MappedQuerySpec<Long> query =
                mock(JdbcClient.MappedQuerySpec.class);

        when(jdbc.sql(anyString())).thenReturn(statement);
        when(statement.param(anyString(), any())).thenReturn(statement);
        when(statement.query(Long.class)).thenReturn(query);
        when(query.single()).thenReturn(2L);

        long count = new AwardArchiveRepository(jdbc)
                .countBudgetPersonnel(213642L);

        assertThat(count).isEqualTo(2L);
        assertThat(firstSql(jdbc))
                .contains("FROM archive.award_budget_personnel_detail")
                .contains("WHERE budget_id = :budgetId");
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
