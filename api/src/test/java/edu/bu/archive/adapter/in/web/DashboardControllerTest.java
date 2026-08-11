package edu.bu.archive.adapter.in.web;

import edu.bu.archive.adapter.in.web.dto.DashboardDto;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DashboardControllerTest {

    @Test
    void usesBusinessCountsAndSeparateHistoryCounts() {
        JdbcClient jdbc = mock(JdbcClient.class);
        JdbcClient.StatementSpec statement =
                mock(JdbcClient.StatementSpec.class);
        @SuppressWarnings("unchecked")
        JdbcClient.MappedQuerySpec<DashboardDto> query =
                mock(JdbcClient.MappedQuerySpec.class);
        DashboardDto expected = new DashboardDto(
                1, 4, 5, 6,
                43057, 281591,
                7, 8,
                9, 10, 0
        );
        when(jdbc.sql(anyString())).thenReturn(statement);
        when(statement.query(DashboardDto.class)).thenReturn(query);
        when(query.single()).thenReturn(expected);

        assertThat(new DashboardController(jdbc).dashboard())
                .isEqualTo(expected);

        String sql = org.mockito.Mockito
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

        assertThat(sql)
                .contains(
                        "COUNT(*) FROM archive.irb_protocol"
                )
                .contains(
                        "COUNT(*) FROM archive.irb_submission"
                )
                .contains(
                        "COUNT(*) FROM archive.irb_funding_source"
                )
                .contains(
                        "COUNT(*) FROM archive.irb_timeline_event"
                )
                .contains(
                        "COUNT(DISTINCT award_number) AS awards"
                )
                .contains(
                        "COUNT(*) AS award_history_records "
                                + "FROM archive.award_version"
                )
                .contains(
                        "COUNT(DISTINCT proposal_number) AS proposals"
                )
                .contains(
                        "COUNT(*) AS proposal_history_records "
                                + "FROM archive.proposal_version"
                )
                .contains(
                        "COUNT(*) FROM archive.negotiation"
                )
                .contains(
                        "COUNT(DISTINCT subaward_code) "
                                + "FROM archive.subaward"
                )
                // Documents = the five-module Kuali document union, per
                // docs/architecture/KUALI_DOCUMENT_METRIC_INVESTIGATION.md
                // - never a hardcoded 0, never attachments, never the
                // Award-nested transactional/financial document tables.
                .contains(
                        "COUNT(DISTINCT (module, document_number)) "
                                + "FROM kuali_documents"
                )
                .contains("'AWARD' AS module, workflow_document_number")
                .contains("'PROPOSAL', document_number")
                .contains("'NEGOTIATION', document_number")
                .contains("'SUBAWARD', document_number")
                .contains(
                        "'IRB', document_number "
                                + "FROM archive.irb_protocol_version"
                )
                .doesNotContain("0 AS documents")
                .doesNotContain(
                        "FROM archive.award_funding_proposal"
                )
                .doesNotContain(
                        "archive.protocol_version"
                )
                .doesNotContain("archive.award_attachment")
                .doesNotContain("archive.attachment_object")
                .doesNotContain("archive.award_budget")
                .doesNotContain("archive.time_and_money_document")
                .doesNotContain("archive.pending_transaction")
                .doesNotContain("archive.award_transmission");
        assertThat(sql.split(
                "FROM archive.award_version",
                -1
        )).hasSize(3);
        assertThat(sql.split(
                "FROM archive.proposal_version",
                -1
        )).hasSize(3);
    }
}
