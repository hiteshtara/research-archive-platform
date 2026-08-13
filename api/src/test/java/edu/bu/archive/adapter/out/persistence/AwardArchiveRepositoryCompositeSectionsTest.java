package edu.bu.archive.adapter.out.persistence;

import edu.bu.archive.adapter.in.web.dto.award.AwardAmountHistoryResponse;
import edu.bu.archive.adapter.in.web.dto.award.AwardAttachmentResponse;
import edu.bu.archive.adapter.in.web.dto.award.AwardCommentRow;
import edu.bu.archive.adapter.in.web.dto.award.AwardNotepadEntryResponse;
import edu.bu.archive.adapter.in.web.dto.award.AwardPersonRow;
import edu.bu.archive.adapter.in.web.dto.award.AwardReportTermRecipientRow;
import edu.bu.archive.adapter.in.web.dto.award.AwardReportTermRow;
import edu.bu.archive.adapter.in.web.dto.award.AwardSapTransmissionChildRow;
import edu.bu.archive.adapter.in.web.dto.award.AwardSponsorTermResponse;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/*
 * SQL-shape tests for the 6 composable Award section queries, in the
 * same mocked-JdbcClient style as AwardArchiveRepositoryTest - these
 * assert the WHERE-clause scoping column and bound parameters, not
 * business logic (that's AwardArchiveServiceCompositeSectionsTest).
 */
class AwardArchiveRepositoryCompositeSectionsTest {

    @Test
    void findPersonRowsScopesByAwardIdNotByCurrentVersion() {
        JdbcClient jdbc = mock(JdbcClient.class);
        JdbcClient.StatementSpec statement =
                mock(JdbcClient.StatementSpec.class);
        @SuppressWarnings("unchecked")
        JdbcClient.MappedQuerySpec<AwardPersonRow> query =
                mock(JdbcClient.MappedQuerySpec.class);

        when(jdbc.sql(anyString())).thenReturn(statement);
        when(statement.param(anyString(), any())).thenReturn(statement);
        when(statement.query(AwardPersonRow.class)).thenReturn(query);
        when(query.list()).thenReturn(List.of());

        new AwardArchiveRepository(jdbc).findPersonRows(3L);

        assertThat(firstSql(jdbc))
                .contains("FROM archive.award_person")
                .contains("WHERE award_id = :awardId")
                .doesNotContain("is_primary_current");
        verify(statement).param("awardId", 3L);
    }

    @Test
    void findNotepadEntriesScopesByAwardNumberNotAwardId() {
        JdbcClient jdbc = mock(JdbcClient.class);
        JdbcClient.StatementSpec statement =
                mock(JdbcClient.StatementSpec.class);
        @SuppressWarnings("unchecked")
        JdbcClient.MappedQuerySpec<AwardNotepadEntryResponse> query =
                mock(JdbcClient.MappedQuerySpec.class);

        when(jdbc.sql(anyString())).thenReturn(statement);
        when(statement.param(anyString(), any())).thenReturn(statement);
        when(statement.query(AwardNotepadEntryResponse.class))
                .thenReturn(query);
        when(query.list()).thenReturn(List.of());

        new AwardArchiveRepository(jdbc)
                .findNotepadEntries("100004-00003");

        // award_notepad has no sequence_number - it is family-wide, so
        // this must key off award_number, unlike every sibling query
        // in this domain which keys off award_id.
        assertThat(firstSql(jdbc))
                .contains("FROM archive.award_notepad")
                .contains("WHERE award_number = :awardNumber")
                .doesNotContain("award_id = :awardId");
        verify(statement).param("awardNumber", "100004-00003");
    }

    @Test
    void findCommentsScopesByAwardNumberLikeNotepadAndJoinsCommentType() {
        JdbcClient jdbc = mock(JdbcClient.class);
        JdbcClient.StatementSpec statement =
                mock(JdbcClient.StatementSpec.class);
        @SuppressWarnings("unchecked")
        JdbcClient.MappedQuerySpec<AwardCommentRow> query =
                mock(JdbcClient.MappedQuerySpec.class);

        when(jdbc.sql(anyString())).thenReturn(statement);
        when(statement.param(anyString(), any())).thenReturn(statement);
        when(statement.query(AwardCommentRow.class)).thenReturn(query);
        when(query.list()).thenReturn(List.of());

        new AwardArchiveRepository(jdbc).findComments("100004-00003");

        // award_comment has its own real sequence_number, but Kuali's
        // Award Comments screen looks up history by awardNumber across
        // every version - the same family-wide bug pattern already
        // fixed for Time and Money - so this must key off award_number,
        // not award_id, and must only surface screen_flag='Y' types.
        assertThat(firstSql(jdbc))
                .contains("FROM archive.comment_type")
                .contains("LEFT JOIN archive.award_comment")
                .contains("ac.award_number = :awardNumber")
                .contains("ct.award_comment_screen_flag = 'Y'")
                .doesNotContain("award_id = :awardId");
        verify(statement).param("awardNumber", "100004-00003");
    }

    @Test
    void countAmountHistoryTreatsANullCountAsZero() {
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
                .countAmountHistory("100004-00003");

        assertThat(total).isZero();
    }

    @Test
    void findAmountHistoryJoinsAwardVersionForEffectiveDateAndOrdersNewestFirst() {
        JdbcClient jdbc = mock(JdbcClient.class);
        JdbcClient.StatementSpec statement =
                mock(JdbcClient.StatementSpec.class);
        @SuppressWarnings("unchecked")
        JdbcClient.MappedQuerySpec<AwardAmountHistoryResponse> query =
                mock(JdbcClient.MappedQuerySpec.class);

        when(jdbc.sql(anyString())).thenReturn(statement);
        when(statement.param(anyString(), any())).thenReturn(statement);
        when(statement.query(AwardAmountHistoryResponse.class))
                .thenReturn(query);
        when(query.list()).thenReturn(List.of());

        new AwardArchiveRepository(jdbc)
                .findAmountHistory("100004-00003", 50, 0);

        assertThat(firstSql(jdbc))
                .contains("FROM archive.award_amount_info amount")
                .contains("INNER JOIN archive.award_version av")
                .contains("av.award_number = :awardNumber")
                .contains("ORDER BY av.sequence_number DESC");
    }

    @Test
    void findTransmissionChildRowsShortCircuitsOnAnEmptyIdCollection() {
        JdbcClient jdbc = mock(JdbcClient.class);

        List<AwardSapTransmissionChildRow> result =
                new AwardArchiveRepository(jdbc)
                        .findTransmissionChildRows(List.of());

        assertThat(result).isEmpty();
        verify(jdbc, never()).sql(anyString());
    }

    @Test
    void findAttachmentsJoinsAttachmentObjectAndComputesDownloadable() {
        JdbcClient jdbc = mock(JdbcClient.class);
        JdbcClient.StatementSpec statement =
                mock(JdbcClient.StatementSpec.class);
        @SuppressWarnings("unchecked")
        JdbcClient.MappedQuerySpec<AwardAttachmentResponse> query =
                mock(JdbcClient.MappedQuerySpec.class);

        when(jdbc.sql(anyString())).thenReturn(statement);
        when(statement.param(anyString(), any())).thenReturn(statement);
        when(statement.query(AwardAttachmentResponse.class))
                .thenReturn(query);
        when(query.list()).thenReturn(List.of());

        new AwardArchiveRepository(jdbc).findAttachments(3L, 25, 0);

        assertThat(firstSql(jdbc))
                .contains("FROM archive.award_attachment aa")
                .contains("LEFT JOIN archive.attachment_object ao")
                .contains("ao.upload_status = 'UPLOADED'")
                .contains("AS downloadable")
                .contains("LIMIT :limit OFFSET :offset");
        verify(statement).param("awardId", 3L);
        verify(statement).param("limit", 25);
        verify(statement).param("offset", 0);
    }

    // --- Terms (V074 reference-data LEFT JOINs) --------------------------

    @Test
    void findSponsorTermsScopesByAwardIdAndJoinsTheSponsorTermLookups() {
        JdbcClient jdbc = mock(JdbcClient.class);
        JdbcClient.StatementSpec statement =
                mock(JdbcClient.StatementSpec.class);
        @SuppressWarnings("unchecked")
        JdbcClient.MappedQuerySpec<AwardSponsorTermResponse> query =
                mock(JdbcClient.MappedQuerySpec.class);

        when(jdbc.sql(anyString())).thenReturn(statement);
        when(statement.param(anyString(), any())).thenReturn(statement);
        when(statement.query(AwardSponsorTermResponse.class))
                .thenReturn(query);
        when(query.list()).thenReturn(List.of());

        new AwardArchiveRepository(jdbc).findSponsorTerms(3L);

        assertThat(firstSql(jdbc))
                .contains("FROM archive.award_sponsor_term ast")
                .contains("LEFT JOIN archive.sponsor_term st")
                .contains("ON st.sponsor_term_id = ast.sponsor_term_id")
                .contains("LEFT JOIN archive.sponsor_term_type stt")
                .contains("ON stt.sponsor_term_type_code = st.sponsor_term_type_code")
                .contains("WHERE ast.award_id = :awardId");
        verify(statement).param("awardId", 3L);
    }

    @Test
    void findReportTermRowsScopesByAwardIdAndJoinsAllFiveReportLookups() {
        JdbcClient jdbc = mock(JdbcClient.class);
        JdbcClient.StatementSpec statement =
                mock(JdbcClient.StatementSpec.class);
        @SuppressWarnings("unchecked")
        JdbcClient.MappedQuerySpec<AwardReportTermRow> query =
                mock(JdbcClient.MappedQuerySpec.class);

        when(jdbc.sql(anyString())).thenReturn(statement);
        when(statement.param(anyString(), any())).thenReturn(statement);
        when(statement.query(AwardReportTermRow.class)).thenReturn(query);
        when(query.list()).thenReturn(List.of());

        new AwardArchiveRepository(jdbc).findReportTermRows(3L);

        assertThat(firstSql(jdbc))
                .contains("FROM archive.award_report_term art")
                .contains("LEFT JOIN archive.report r")
                .contains("LEFT JOIN archive.report_class rc")
                .contains("LEFT JOIN archive.frequency f")
                .contains("LEFT JOIN archive.frequency_base fb")
                .contains("LEFT JOIN archive.distribution d")
                .contains("WHERE art.award_id = :awardId");
        verify(statement).param("awardId", 3L);
    }

    @Test
    void findReportTermRecipientRowsScopesByAwardIdAndJoinsContactType() {
        JdbcClient jdbc = mock(JdbcClient.class);
        JdbcClient.StatementSpec statement =
                mock(JdbcClient.StatementSpec.class);
        @SuppressWarnings("unchecked")
        JdbcClient.MappedQuerySpec<AwardReportTermRecipientRow> query =
                mock(JdbcClient.MappedQuerySpec.class);

        when(jdbc.sql(anyString())).thenReturn(statement);
        when(statement.param(anyString(), any())).thenReturn(statement);
        when(statement.query(AwardReportTermRecipientRow.class))
                .thenReturn(query);
        when(query.list()).thenReturn(List.of());

        new AwardArchiveRepository(jdbc).findReportTermRecipientRows(3L);

        assertThat(firstSql(jdbc))
                .contains("FROM archive.award_report_term_recipient artr")
                .contains("LEFT JOIN archive.contact_type ct")
                .contains("ON ct.contact_type_code = artr.contact_type_code")
                .contains("WHERE artr.award_id = :awardId");
        verify(statement).param("awardId", 3L);
    }

    @Test
    void countAttachmentsTreatsANullCountAsZero() {
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

        long total = new AwardArchiveRepository(jdbc).countAttachments(3L);

        assertThat(total).isZero();
        assertThat(firstSql(jdbc))
                .contains("SELECT COUNT(*)")
                .contains("FROM archive.award_attachment")
                .contains("WHERE award_id = :awardId");
    }

    private String firstSql(JdbcClient jdbc) {
        return mockingDetails(jdbc)
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
