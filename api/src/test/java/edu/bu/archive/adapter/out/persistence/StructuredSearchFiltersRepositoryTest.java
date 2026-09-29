package edu.bu.archive.adapter.out.persistence;

import edu.bu.archive.adapter.in.web.dto.proposal.ProposalFamilySummaryResponse;
import edu.bu.archive.adapter.in.web.dto.subaward.SubawardSummaryResponse;
import edu.bu.archive.application.proposal.ProposalSearchFilters;
import edu.bu.archive.application.subaward.SubawardSearchFilters;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/*
 * Structured Subaward and Proposal search filters - SQL shape and
 * parameter binding against a mocked JdbcClient. No database.
 */
class StructuredSearchFiltersRepositoryTest {

    @SuppressWarnings("unchecked")
    private static <T> JdbcClient.StatementSpec stub(
            JdbcClient jdbc, Class<T> type, Long single
    ) {
        JdbcClient.StatementSpec statement = mock(JdbcClient.StatementSpec.class);
        JdbcClient.MappedQuerySpec<T> query = mock(JdbcClient.MappedQuerySpec.class);
        when(jdbc.sql(anyString())).thenReturn(statement);
        when(statement.param(anyString(), any())).thenReturn(statement);
        when(statement.query(type)).thenReturn(query);
        when(query.list()).thenReturn(List.of());
        if (single != null) {
            when(query.single()).thenReturn((T) single);
        }
        return statement;
    }

    private static String sql(JdbcClient jdbc) {
        return org.mockito.Mockito.mockingDetails(jdbc).getInvocations().stream()
                .filter(invocation -> invocation.getMethod().getName().equals("sql"))
                .map(invocation -> (String) invocation.getArgument(0))
                .findFirst()
                .orElseThrow()
                .replaceAll("\\s+", " ");
    }

    private static final SubawardSearchFilters SUBAWARD_FILTERS = new SubawardSearchFilters(
            "Executed", "National Cancer", "ORG-1",
            LocalDate.of(2019, 1, 1), LocalDate.of(2019, 12, 31),
            LocalDate.of(2020, 1, 1), LocalDate.of(2024, 6, 30)
    );

    // --- Subaward --------------------------------------------------------

    @Test
    void unfilteredSubawardSqlIsUnchangedByTheFilterSupport() {
        JdbcClient legacy = mock(JdbcClient.class);
        stub(legacy, SubawardSummaryResponse.class, null);
        new SubawardArchiveRepository(legacy).findSubawards("", 25, 0);

        JdbcClient explicit = mock(JdbcClient.class);
        JdbcClient.StatementSpec statement =
                stub(explicit, SubawardSummaryResponse.class, null);
        new SubawardArchiveRepository(explicit)
                .findSubawards("", SubawardSearchFilters.none(), 25, 0);

        assertThat(sql(explicit)).isEqualTo(sql(legacy))
                .doesNotContain("WHERE")
                .contains("ORDER BY subaward_id DESC");
        verify(statement, never()).param(eq("status"), any());
    }

    @Test
    void subawardFiltersAreAndedOutsideTheParenthesizedTextAndFrnPredicate() {
        JdbcClient jdbc = mock(JdbcClient.class);
        JdbcClient.StatementSpec statement =
                stub(jdbc, SubawardSummaryResponse.class, null);

        // A 10-digit query: the gated FRN branches are emitted.
        new SubawardArchiveRepository(jdbc)
                .findSubawards("4500002829", SUBAWARD_FILTERS, 25, 0);

        String sql = sql(jdbc);
        assertThat(sql)
                .contains("WHERE ( CAST(s.subaward_id AS TEXT)")
                .contains("sa.purchase_order_num ILIKE '%' || :query || '%'")
                // the whole OR chain closes before the first AND
                .containsPattern("sa\\.purchase_order_num ILIKE '%' \\|\\| :query \\|\\| '%' \\) \\) AND \\(UPPER")
                .contains("UPPER(TRIM(s.status_description)) = UPPER(:status)")
                .contains("regexp_replace( s.status_description, '^[0-9]+\\.\\s*', '')")
                .contains("s.award_sponsor_name ILIKE '%' || :sponsor || '%'")
                .contains("s.award_prime_sponsor_name ILIKE '%' || :sponsor || '%'")
                .contains("UPPER(TRIM(s.organization_id)) = UPPER(:organizationId)")
                .contains("s.start_date >= CAST(:startDateFrom AS DATE)")
                .contains("s.start_date <= CAST(:startDateTo AS DATE)")
                .contains("s.end_date >= CAST(:endDateFrom AS DATE)")
                .contains("s.end_date <= CAST(:endDateTo AS DATE)");
        verify(statement).param("query", "4500002829");
        verify(statement).param("status", "Executed");
        verify(statement).param("sponsor", "National Cancer");
        verify(statement).param("organizationId", "ORG-1");
        verify(statement).param("endDateTo", LocalDate.of(2024, 6, 30));
    }

    @Test
    void subawardFiltersWithoutFreeTextStillUsePrimaryKeyOrder() {
        JdbcClient jdbc = mock(JdbcClient.class);
        JdbcClient.StatementSpec statement =
                stub(jdbc, SubawardSummaryResponse.class, null);

        new SubawardArchiveRepository(jdbc).findSubawards(
                null,
                new SubawardSearchFilters(null, null, "ORG-1", null, null, null, null),
                25, 0);

        assertThat(sql(jdbc))
                .contains("WHERE UPPER(TRIM(s.organization_id)) = UPPER(:organizationId)")
                .contains("ORDER BY subaward_id DESC")
                .doesNotContain(":query")
                .doesNotContain(":status");
        verify(statement, never()).param(eq("query"), any());
    }

    @Test
    void subawardCountUsesTheSameWhereAsThePage() {
        JdbcClient pageJdbc = mock(JdbcClient.class);
        stub(pageJdbc, SubawardSummaryResponse.class, null);
        new SubawardArchiveRepository(pageJdbc)
                .findSubawards("cancer", SUBAWARD_FILTERS, 25, 0);

        JdbcClient countJdbc = mock(JdbcClient.class);
        stub(countJdbc, Long.class, 12L);
        long total = new SubawardArchiveRepository(countJdbc)
                .countSubawards("cancer", SUBAWARD_FILTERS);

        String page = sql(pageJdbc);
        String count = sql(countJdbc);
        assertThat(total).isEqualTo(12L);
        assertThat(count.substring(count.indexOf("WHERE")).trim())
                .isEqualTo(page.substring(page.indexOf("WHERE"), page.lastIndexOf("ORDER BY")).trim());
    }

    // --- Proposal --------------------------------------------------------

    @Test
    void proposalFamilyPageFiltersTheLatestVersionAndPaginates() {
        JdbcClient jdbc = mock(JdbcClient.class);
        JdbcClient.StatementSpec statement =
                stub(jdbc, ProposalFamilySummaryResponse.class, null);

        new ProposalArchiveRepository(jdbc).findFamilyPage(
                "  cancer ",
                new ProposalSearchFilters("NIH", "Smith", "Chemistry"),
                25, 50);

        assertThat(sql(jdbc))
                .contains("PARTITION BY proposal_number")
                .contains("WHERE row_rank = 1")
                .contains("sponsor_name ILIKE '%' || :sponsor || '%'")
                .contains("sponsor_code ILIKE '%' || :sponsor || '%'")
                .contains("lead_unit_name ILIKE '%' || :leadUnit || '%'")
                .contains("lead_unit_number ILIKE '%' || :leadUnit || '%'")
                .contains("principal_investigator_name ILIKE '%' || :principalInvestigator || '%'")
                .contains("ORDER BY proposal_number LIMIT :limit OFFSET :offset");
        verify(statement).param("query", "cancer");
        verify(statement).param("sponsor", "NIH");
        verify(statement).param("principalInvestigator", "Smith");
        verify(statement).param("leadUnit", "Chemistry");
        verify(statement).param("limit", 25);
        verify(statement).param("offset", 50);
    }

    @Test
    void proposalCountSharesTheRankingAndWhereOfThePage() {
        JdbcClient pageJdbc = mock(JdbcClient.class);
        stub(pageJdbc, ProposalFamilySummaryResponse.class, null);
        new ProposalArchiveRepository(pageJdbc)
                .findFamilyPage(null, ProposalSearchFilters.none(), 25, 0);

        JdbcClient countJdbc = mock(JdbcClient.class);
        JdbcClient.StatementSpec countStatement = stub(countJdbc, Long.class, 33L);
        long total = new ProposalArchiveRepository(countJdbc)
                .countFamilyPage(null, ProposalSearchFilters.none());

        String page = sql(pageJdbc);
        String count = sql(countJdbc);
        assertThat(total).isEqualTo(33L);
        assertThat(count).contains("SELECT COUNT(*) FROM ranked");
        assertThat(count.substring(count.indexOf("WHERE row_rank")).trim())
                .isEqualTo(page.substring(page.indexOf("WHERE row_rank"), page.lastIndexOf("ORDER BY")).trim());
        // A blank query and no filters bind nulls - "browse all".
        verify(countStatement).param("query", null);
        verify(countStatement).param("sponsor", null);
    }
}
