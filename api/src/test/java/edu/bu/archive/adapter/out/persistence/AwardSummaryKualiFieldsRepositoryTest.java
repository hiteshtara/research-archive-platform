package edu.bu.archive.adapter.out.persistence;

import edu.bu.archive.adapter.in.web.dto.award.AwardSummaryResponse;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Source-mapping contract for the Kuali-labelled Award Summary fields.
 *
 * The Summary screen must use the same field names BU staff know from
 * the legacy Kuali Award screen, and every one of them must come from
 * the field Kuali itself reads. Two mappings here were empirically
 * wrong-looking and are easy to "correct" backwards later, so both are
 * pinned:
 *
 *   - Project Start Date is AWARD.AWARD_EFFECTIVE_DATE, NOT
 *     AWARD.BEGIN_DATE. begin_date is populated in 2 of 267,386 Oracle
 *     AWARD rows and is NULL for every sequence of the reconciliation
 *     fixture 105698-00001, whose Kuali screen nonetheless shows
 *     04/01/2007 - exactly its award_effective_date.
 *   - Obligation Start Date is award_amount_info.
 *     current_fund_effective_date read through Kuali's current-row rule
 *     (MAX(award_amount_info_id)), NOT award_version.closeout_date.
 */
class AwardSummaryKualiFieldsRepositoryTest {

    private static String summarySql() {
        JdbcClient jdbc = mock(JdbcClient.class);
        JdbcClient.StatementSpec statement =
                mock(JdbcClient.StatementSpec.class);
        @SuppressWarnings("unchecked")
        JdbcClient.MappedQuerySpec<AwardSummaryResponse> query =
                mock(JdbcClient.MappedQuerySpec.class);

        when(jdbc.sql(anyString())).thenReturn(statement);
        when(statement.param(anyString(), any())).thenReturn(statement);
        when(statement.query(AwardSummaryResponse.class)).thenReturn(query);
        when(query.optional()).thenReturn(Optional.empty());

        new AwardArchiveRepository(jdbc).findSummaryByAwardId(2727052L);

        org.mockito.ArgumentCaptor<String> sql =
                org.mockito.ArgumentCaptor.forClass(String.class);
        org.mockito.Mockito.verify(jdbc).sql(sql.capture());
        return sql.getValue();
    }

    @Test
    void projectStartDateIsAwardEffectiveDateNeverBeginDate() {
        assertThat(summarySql())
                .contains("av.award_effective_date")
                .doesNotContain("av.begin_date AS award_effective_date")
                .doesNotContain("av.begin_date AS project_start_date");
    }

    @Test
    void obligationStartDateComesFromTheCurrentAmountRowNotCloseoutDate() {
        assertThat(summarySql())
                .contains(
                        "amt.current_fund_effective_date "
                                + "AS obligation_start_date")
                .doesNotContain("av.closeout_date AS obligation_start_date");
    }

    /**
     * The obligation date must be selected inside the SAME LATERAL that
     * picks the current amount row, so the date and the amounts on one
     * Summary can never disagree about which row was current.
     */
    @Test
    void obligationStartDateUsesKualisMaxAwardAmountInfoIdRule() {
        String sql = summarySql();

        int lateralStart = sql.indexOf("ai.current_fund_effective_date");
        assertThat(lateralStart).isGreaterThan(-1);

        String afterDate = sql.substring(lateralStart);
        int limit = afterDate.indexOf("LIMIT 1");
        assertThat(limit).isGreaterThan(-1);

        assertThat(afterDate.substring(0, limit))
                .contains("FROM archive.award_amount_info ai")
                .contains("ai.award_amount_info_id DESC")
                .doesNotContain("source_version_number");
    }

    @Test
    void alnNumberAndTitleComeFromTheSameAwardCfdaRow() {
        String sql = summarySql();

        assertThat(sql)
                .contains("cfda.cfda_number AS aln_number")
                .contains("cfda.cfda_description AS aln_program_title_name")
                .contains("FROM archive.award_cfda ac");

        // Both columns must be projected from one lateral alias, so a
        // number can never be paired with another row's title.
        int number = sql.indexOf("cfda.cfda_number AS aln_number");
        int title = sql.indexOf("cfda.cfda_description AS aln_program_title_name");
        assertThat(title).isGreaterThan(number);
    }

    @Test
    void sponsorAndPrimeSponsorIdentifiersAreSelected() {
        assertThat(summarySql())
                .contains("av.sponsor_code")
                .contains("av.sponsor_award_number")
                .contains("av.prime_sponsor_code")
                .contains("ext.prime_sponsor_award_id")
                .contains("av.modification_number");
    }

    @Test
    void fainIdAndNsfScienceCodeAreSelectedFromTheArchivedColumns() {
        assertThat(summarySql())
                .contains("av.fain_id")
                .contains("av.nsf_science_code")
                .contains("av.nsf_sequence_number");
    }

    @Test
    void institutionFieldsResolveFromAwardVersionAndAwardExtension() {
        assertThat(summarySql())
                .contains("ext.grant_number")
                .contains("ext.federal_clinical_trial")
                .contains("av.account_type")
                .contains("av.activity_type")
                .contains("av.award_type")
                .contains("LEFT JOIN archive.award_extension ext");
    }

    /**
     * account_type here is AWARD.ACCOUNT_TYPE_CODE resolved against
     * ACCOUNT_TYPE - a real general Award attribute. It must never be
     * sourced from archive.award_transmission.account_type_code, which
     * is SAP-transmission-specific.
     */
    @Test
    void accountTypeIsNotSourcedFromSapTransmission() {
        assertThat(summarySql()).doesNotContain("award_transmission");
    }

    @Test
    void federalAwardYearIsNotSelectedAtAll() {
        assertThat(summarySql())
                .doesNotContain("fed_award_year")
                .doesNotContain("federal_award_year");
    }

    // --- Grant Number search -------------------------------------------
    //
    // Grant Number is AWARD_EXTENSION.GRANT_NUMBER (86.4% populated).
    // Award 105698-00001 carries Grant Number 50105698 - a value that
    // shares no substring with its award_number, so without an explicit
    // grant_number predicate searching "50105698" finds nothing.

    private static String searchSql() {
        JdbcClient jdbc = mock(JdbcClient.class);
        JdbcClient.StatementSpec statement =
                mock(JdbcClient.StatementSpec.class);
        @SuppressWarnings("unchecked")
        JdbcClient.MappedQuerySpec<
                edu.bu.archive.adapter.in.web.dto.award
                        .AwardSearchResultResponse> query =
                mock(JdbcClient.MappedQuerySpec.class);

        when(jdbc.sql(anyString())).thenReturn(statement);
        when(statement.param(anyString(), any())).thenReturn(statement);
        when(statement.query(
                edu.bu.archive.adapter.in.web.dto.award
                        .AwardSearchResultResponse.class))
                .thenReturn(query);
        when(query.list()).thenReturn(java.util.List.of());

        new AwardArchiveRepository(jdbc)
                .searchAwards("%50105698%", "50105698", 25, 0);

        org.mockito.ArgumentCaptor<String> sql =
                org.mockito.ArgumentCaptor.forClass(String.class);
        org.mockito.Mockito.verify(jdbc).sql(sql.capture());
        return sql.getValue();
    }

    @Test
    void awardSearchMatchesOnGrantNumberExactlyAndPartially() {
        assertThat(searchSql())
                .contains("UPPER(ext2.grant_number)")
                .contains("ext2.grant_number ILIKE :pattern");
    }

    /**
     * award_extension is keyed by award_id, so Grant Number is
     * VERSION-scoped, while searchAwards only ever returns rows where
     * is_primary_current = TRUE. Matching must therefore span every
     * version sharing the award_number, or an Award whose Grant Number
     * exists only on an earlier version becomes unfindable.
     */
    @Test
    void grantNumberMatchingSpansEveryVersionOfTheAwardFamily() {
        String sql = searchSql();
        int exists = sql.indexOf("FROM archive.award_version av2");
        assertThat(exists).isGreaterThan(-1);
        assertThat(sql.substring(exists))
                .contains("JOIN archive.award_extension ext2")
                .contains("av2.award_number = av.award_number");
    }

    @Test
    void awardSearchReturnsGrantNumberAsResultMetadata() {
        assertThat(searchSql())
                .contains("ext.grant_number")
                .contains("LEFT JOIN archive.award_extension ext");
    }

    @Test
    void summaryExposesGrantNumberFromAwardExtension() {
        assertThat(summarySql()).contains("ext.grant_number");
    }
}
