package edu.bu.archive.adapter.out.persistence;

import edu.bu.archive.adapter.in.web.dto.proposal.ProposalCustomDataResponse;
import edu.bu.archive.adapter.in.web.dto.proposal.ProposalFundedAwardResponse;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ProposalV1RepositoryTest {

    @Test
    void findCustomDataRowsResolvesTheLookupAndScopesByExactProposalId() {
        JdbcClient jdbc = mock(JdbcClient.class);
        JdbcClient.StatementSpec statement =
                mock(JdbcClient.StatementSpec.class);
        @SuppressWarnings("unchecked")
        JdbcClient.MappedQuerySpec<ProposalCustomDataResponse> query =
                mock(JdbcClient.MappedQuerySpec.class);

        when(jdbc.sql(anyString())).thenReturn(statement);
        when(statement.param(anyString(), any())).thenReturn(statement);
        when(statement.query(ProposalCustomDataResponse.class))
                .thenReturn(query);
        when(query.list()).thenReturn(List.of());

        new ProposalV1Repository(jdbc).findCustomDataRows(2986L);

        // custom_attribute_id has no foreign key (V064) - this must be a
        // LEFT JOIN, never an inner join, so a row with no matching
        // lookup still comes back. Scoped by the exact proposal_id
        // (version-scoped), never proposal_number (which would fan out
        // across sibling versions and silently combine their data).
        assertThat(firstSql(jdbc))
                .contains("FROM archive.proposal_custom_data pcd")
                .contains("LEFT JOIN archive.custom_attribute ca")
                .contains("pcd.proposal_id = :proposalId")
                .doesNotContain("proposal_number");
    }

    @Test
    void findFundedAwardRowsSelectsTheSourceRelationshipIdAndNeverDeduplicates() {
        // V075 allowed genuine natural-key duplicates in
        // archive.proposal_award to coexist (e.g. Proposal family 2975/
        // award_id 462515) - this method's own contract is "one row per
        // real relationship row", so it must select
        // award_funding_proposal_id (the real Oracle PK) so a client can
        // trace/group duplicates, and must never introduce a DISTINCT/
        // GROUP BY/window-function collapse.
        JdbcClient jdbc = mock(JdbcClient.class);
        JdbcClient.StatementSpec statement =
                mock(JdbcClient.StatementSpec.class);
        @SuppressWarnings("unchecked")
        JdbcClient.MappedQuerySpec<ProposalFundedAwardResponse> query =
                mock(JdbcClient.MappedQuerySpec.class);

        when(jdbc.sql(anyString())).thenReturn(statement);
        when(statement.param(anyString(), any())).thenReturn(statement);
        when(statement.query(ProposalFundedAwardResponse.class))
                .thenReturn(query);
        when(query.list()).thenReturn(List.of());

        new ProposalV1Repository(jdbc).findFundedAwardRows("205");

        assertThat(firstSql(jdbc))
                .contains("pa.award_funding_proposal_id AS source_relationship_id")
                .doesNotContain("DISTINCT")
                .doesNotContain("GROUP BY")
                .doesNotContain("ROW_NUMBER");
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
