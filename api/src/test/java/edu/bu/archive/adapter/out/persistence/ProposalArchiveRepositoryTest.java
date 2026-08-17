package edu.bu.archive.adapter.out.persistence;

import edu.bu.archive.adapter.in.web.dto.proposal.ProposalAwardResponse;
import edu.bu.archive.adapter.in.web.dto.proposal.ProposalRowResponse;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.util.List;
import java.util.Optional;

import static edu.bu.archive.testsupport.ProposalFixtures.proposalRow;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ProposalArchiveRepositoryTest {

    @Test
    void findCurrentMapsTheCurrentProposalRow() {
        JdbcClient jdbc = mock(JdbcClient.class);
        JdbcClient.StatementSpec statement =
                mock(JdbcClient.StatementSpec.class);
        @SuppressWarnings("unchecked")
        JdbcClient.MappedQuerySpec<ProposalRowResponse> query =
                mock(JdbcClient.MappedQuerySpec.class);

        ProposalRowResponse expected = proposalRow();

        when(jdbc.sql(anyString())).thenReturn(statement);
        when(statement.param(
                "proposalNumber",
                "P-100"
        )).thenReturn(statement);
        when(statement.query(
                ProposalRowResponse.class
        )).thenReturn(query);
        when(query.optional()).thenReturn(
                Optional.of(expected)
        );

        ProposalArchiveRepository repository =
                new ProposalArchiveRepository(jdbc);

        Optional<ProposalRowResponse> result =
                repository.findCurrent("P-100");

        assertThat(result).contains(expected);
        verify(statement).param(
                "proposalNumber",
                "P-100"
        );
    }

    @Test
    void findAwardsRanksOneRelationshipPerAward() {
        JdbcClient jdbc = mock(JdbcClient.class);
        JdbcClient.StatementSpec statement =
                mock(JdbcClient.StatementSpec.class);
        @SuppressWarnings("unchecked")
        JdbcClient.MappedQuerySpec<ProposalAwardResponse> query =
                mock(JdbcClient.MappedQuerySpec.class);

        when(jdbc.sql(anyString())).thenReturn(statement);
        when(statement.param(
                "proposalNumber",
                "P-100"
        )).thenReturn(statement);
        when(statement.query(
                ProposalAwardResponse.class
        )).thenReturn(query);
        when(query.list()).thenReturn(List.of());

        ProposalArchiveRepository repository =
                new ProposalArchiveRepository(jdbc);

        repository.findAwards("P-100");

        String sql = org.mockito.Mockito
                .mockingDetails(jdbc)
                .getInvocations()
                .stream()
                .filter(invocation ->
                        invocation.getMethod()
                                .getName()
                                .equals("sql")
                )
                .map(invocation ->
                        (String) invocation.getArgument(0)
                )
                .findFirst()
                .orElseThrow()
                .replaceAll("\\s+", " ");

        assertThat(sql)
                .contains(
                        "PARTITION BY relationship.award_id"
                )
                .contains("WHERE row_rank = 1");
    }

    @Test
    void findAwardsBreaksTiesByAwardFundingProposalIdForDeterministicOrdering() {
        // V075 allowed two archive.proposal_award rows to legitimately
        // share (proposal_id, award_id) - without this tiebreaker,
        // row_rank = 1's winner would be plan-dependent whenever those
        // two rows also tie on proposal_id (they always do, since it's
        // the same proposal_id on both sides of the duplicate).
        JdbcClient jdbc = mock(JdbcClient.class);
        JdbcClient.StatementSpec statement =
                mock(JdbcClient.StatementSpec.class);
        @SuppressWarnings("unchecked")
        JdbcClient.MappedQuerySpec<ProposalAwardResponse> query =
                mock(JdbcClient.MappedQuerySpec.class);

        when(jdbc.sql(anyString())).thenReturn(statement);
        when(statement.param(
                "proposalNumber",
                "P-100"
        )).thenReturn(statement);
        when(statement.query(
                ProposalAwardResponse.class
        )).thenReturn(query);
        when(query.list()).thenReturn(List.of());

        ProposalArchiveRepository repository =
                new ProposalArchiveRepository(jdbc);

        repository.findAwards("P-100");

        String sql = org.mockito.Mockito
                .mockingDetails(jdbc)
                .getInvocations()
                .stream()
                .filter(invocation ->
                        invocation.getMethod()
                                .getName()
                                .equals("sql")
                )
                .map(invocation ->
                        (String) invocation.getArgument(0)
                )
                .findFirst()
                .orElseThrow()
                .replaceAll("\\s+", " ");

        assertThat(sql)
                .contains(
                        "relationship.proposal_id DESC, relationship.award_funding_proposal_id DESC"
                );
    }

    // --- Global Search semantic-result enrichment ---------------------------

    @Test
    void findCurrentSummariesForNumbersRanksTheLatestVersionAndBindsTheSetOfProposalNumbers() {
        JdbcClient jdbc = mock(JdbcClient.class);
        JdbcClient.StatementSpec statement = mock(JdbcClient.StatementSpec.class);
        @SuppressWarnings("unchecked")
        JdbcClient.MappedQuerySpec<ProposalSemanticSummaryRow> query =
                mock(JdbcClient.MappedQuerySpec.class);

        when(jdbc.sql(anyString())).thenReturn(statement);
        when(statement.param(anyString(), any())).thenReturn(statement);
        when(statement.query(ProposalSemanticSummaryRow.class)).thenReturn(query);
        when(query.list()).thenReturn(List.of());

        new ProposalArchiveRepository(jdbc).findCurrentSummariesForNumbers(
                List.of("01117952", "01099385")
        );

        assertThat(firstSql(jdbc))
                .contains("FROM archive.proposal_version")
                .contains("proposal_number IN (:proposalNumbers)")
                .contains("ROW_NUMBER() OVER (")
                .contains("PARTITION BY proposal_number")
                .contains("WHERE row_rank = 1")
                .doesNotContain("embedding")
                .doesNotContain("distance");
        verify(statement).param(
                "proposalNumbers", List.of("01117952", "01099385")
        );
    }

    @Test
    void findCurrentSummariesForNumbersNeverQueriesForAnEmptySet() {
        JdbcClient jdbc = mock(JdbcClient.class);

        List<ProposalSemanticSummaryRow> results =
                new ProposalArchiveRepository(jdbc)
                        .findCurrentSummariesForNumbers(List.of());

        assertThat(results).isEmpty();
        verifyNoInteractions(jdbc);
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
