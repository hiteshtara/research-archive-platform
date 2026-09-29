package edu.bu.archive.adapter.out.persistence;

import edu.bu.archive.adapter.in.web.dto.award.AwardSearchResultResponse;
import edu.bu.archive.adapter.in.web.dto.award.AwardVersionSearchResultResponse;
import edu.bu.archive.application.award.AwardSearchFilters;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/*
 * Structured Award search filters (current-Award and Historical Award
 * Records searches). SQL-shape tests against a mocked JdbcClient, like
 * AwardArchiveRepositoryTest - no database.
 */
class AwardSearchFiltersRepositoryTest {

    private static final AwardSearchFilters FILTERS = new AwardSearchFilters(
            "Active", "NIH", "Smith", "Chemistry",
            LocalDate.of(2020, 1, 1), LocalDate.of(2020, 12, 31)
    );

    @SuppressWarnings("unchecked")
    private static <T> JdbcClient.StatementSpec stub(
            JdbcClient jdbc, Class<T> type, List<T> list, Long single
    ) {
        JdbcClient.StatementSpec statement = mock(JdbcClient.StatementSpec.class);
        JdbcClient.MappedQuerySpec<T> query = mock(JdbcClient.MappedQuerySpec.class);
        when(jdbc.sql(anyString())).thenReturn(statement);
        when(statement.param(anyString(), any())).thenReturn(statement);
        when(statement.query(type)).thenReturn(query);
        when(query.list()).thenReturn(list);
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

    private static String whereOf(String sql) {
        int start = sql.indexOf("WHERE av.is_primary_current = TRUE");
        int end = sql.lastIndexOf("ORDER BY");
        return (end < 0 ? sql.substring(start) : sql.substring(start, end)).trim();
    }

    @Test
    void familySearchAndCountShareTheIdenticalWhereClauseIncludingGrantNumber() {
        JdbcClient pageJdbc = mock(JdbcClient.class);
        stub(pageJdbc, AwardSearchResultResponse.class, List.of(), null);
        new AwardArchiveRepository(pageJdbc)
                .searchAwards("%x%", "x", FILTERS, 25, 0);

        JdbcClient countJdbc = mock(JdbcClient.class);
        stub(countJdbc, Long.class, List.of(), 7L);
        long total = new AwardArchiveRepository(countJdbc)
                .countSearchAwards("%x%", "x", FILTERS);

        assertThat(total).isEqualTo(7L);
        // Regression: the count used to omit the family-wide Grant Number
        // branch, so a Grant-Number-only match was returned but not counted.
        assertThat(sql(countJdbc)).contains("ext2.grant_number ILIKE :pattern");
        assertThat(whereOf(sql(countJdbc))).isEqualTo(whereOf(sql(pageJdbc)));
    }

    @Test
    void unfilteredLegacySignaturesStillCountGrantNumberMatches() {
        JdbcClient countJdbc = mock(JdbcClient.class);
        JdbcClient.StatementSpec statement = stub(countJdbc, Long.class, List.of(), 3L);

        new AwardArchiveRepository(countJdbc).countSearchAwards("%50105698%", "50105698");

        assertThat(sql(countJdbc))
                .contains("UPPER(ext2.grant_number) = UPPER(:rawQuery)")
                .contains("CAST(:status AS TEXT) IS NULL");
        // No filters: every structured parameter is bound as null, so each
        // condition short-circuits to true.
        verify(statement).param("status", null);
        verify(statement).param("sponsor", null);
        verify(statement).param("principalInvestigator", null);
        verify(statement).param("leadUnit", null);
        verify(statement).param("projectStartDateFrom", null);
        verify(statement).param("projectStartDateTo", null);
    }

    @Test
    void familySearchAppliesEveryStructuredFilterWithDocumentedSemantics() {
        JdbcClient jdbc = mock(JdbcClient.class);
        JdbcClient.StatementSpec statement =
                stub(jdbc, AwardSearchResultResponse.class, List.of(), null);

        new AwardArchiveRepository(jdbc).searchAwards("", "", FILTERS, 25, 50);

        assertThat(sql(jdbc))
                // status: exact, case-insensitive (never a substring)
                .contains("UPPER(TRIM(av.status_description)) = UPPER(CAST(:status AS TEXT))")
                // sponsor / lead unit: contains, name OR code/number
                .contains("av.sponsor_name ILIKE '%' || :sponsor || '%'")
                .contains("av.sponsor_code ILIKE '%' || :sponsor || '%'")
                .contains("av.lead_unit_name ILIKE '%' || :leadUnit || '%'")
                .contains("av.lead_unit_number ILIKE '%' || :leadUnit || '%'")
                // PI: contains, PI role only, on the returned version
                .contains("UPPER(TRIM(apf.contact_role_code)) = 'PI'")
                .contains("apf.award_id = av.award_id")
                // Project Start Date is award_effective_date, inclusive
                .contains("av.award_effective_date >= CAST(:projectStartDateFrom AS DATE)")
                .contains("av.award_effective_date <= CAST(:projectStartDateTo AS DATE)")
                .doesNotContain("begin_date")
                // filters narrow the current-Award grain, never widen it
                .contains("WHERE av.is_primary_current = TRUE");
        verify(statement).param("status", "Active");
        verify(statement).param("sponsor", "NIH");
        verify(statement).param("principalInvestigator", "Smith");
        verify(statement).param("leadUnit", "Chemistry");
        verify(statement).param("projectStartDateFrom", LocalDate.of(2020, 1, 1));
        verify(statement).param("projectStartDateTo", LocalDate.of(2020, 12, 31));
        verify(statement).param("offset", 50);
    }

    @Test
    void versionSearchAndCountShareTheSameWhereAndApplyTheFilters() {
        JdbcClient pageJdbc = mock(JdbcClient.class);
        JdbcClient.StatementSpec pageStatement =
                stub(pageJdbc, AwardVersionSearchResultResponse.class, List.of(), null);
        new AwardArchiveRepository(pageJdbc).searchAwardVersions(
                "%x%", "x", "", "", null, "historical", FILTERS,
                "ORDER BY av.sequence_number DESC\n", 25, 0);

        JdbcClient countJdbc = mock(JdbcClient.class);
        stub(countJdbc, Long.class, List.of(), 4L);
        long total = new AwardArchiveRepository(countJdbc).countSearchAwardVersions(
                "%x%", "x", "", "", null, "historical", FILTERS);

        String page = sql(pageJdbc);
        String count = sql(countJdbc);
        assertThat(total).isEqualTo(4L);
        assertThat(page)
                .contains("(:versionFilter = 'historical' AND av.is_primary_current = FALSE)")
                .contains("UPPER(TRIM(av.status_description)) = UPPER(CAST(:status AS TEXT))")
                .contains("av.award_effective_date <= CAST(:projectStartDateTo AS DATE)");
        String pageWhere = page.substring(page.indexOf("WHERE (:awardNumber"), page.lastIndexOf("ORDER BY"));
        String countWhere = count.substring(count.indexOf("WHERE (:awardNumber"));
        assertThat(countWhere.trim()).isEqualTo(pageWhere.trim());
        verify(pageStatement).param("sponsor", "NIH");
        verify(pageStatement).param("versionFilter", "historical");
    }

    @Test
    void blankFilterValuesAreTreatedAsAbsent() {
        AwardSearchFilters blank = new AwardSearchFilters("  ", "", " ", "", null, null);

        assertThat(blank).isEqualTo(AwardSearchFilters.none());
        assertThat(blank.hasStructuredFilters()).isFalse();
        assertThat(FILTERS.hasStructuredFilters()).isTrue();
    }
}
