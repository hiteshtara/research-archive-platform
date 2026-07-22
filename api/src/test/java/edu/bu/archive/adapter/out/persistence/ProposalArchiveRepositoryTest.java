package edu.bu.archive.adapter.out.persistence;

import edu.bu.archive.adapter.in.web.dto.proposal.ProposalAwardResponse;
import edu.bu.archive.adapter.in.web.dto.proposal.ProposalPersonResponse;
import edu.bu.archive.adapter.in.web.dto.proposal.ProposalRowResponse;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
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
    void findCurrentPeopleExcludesPeopleFromOlderVersions() {
        JdbcClient jdbc = mock(JdbcClient.class);
        JdbcClient.StatementSpec statement =
                mock(JdbcClient.StatementSpec.class);
        @SuppressWarnings("unchecked")
        JdbcClient.MappedQuerySpec<ProposalPersonResponse> query =
                mock(JdbcClient.MappedQuerySpec.class);

        when(jdbc.sql(anyString())).thenReturn(statement);
        when(statement.param(
                "proposalNumber",
                "P-100"
        )).thenReturn(statement);
        when(statement.query(
                ProposalPersonResponse.class
        )).thenReturn(query);
        when(query.list()).thenReturn(List.of());

        ProposalArchiveRepository repository =
                new ProposalArchiveRepository(jdbc);

        repository.findCurrentPeople("P-100");

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
                        "ORDER BY version_number DESC, "
                                + "source_update_timestamp DESC NULLS LAST, "
                                + "proposal_id DESC"
                )
                .contains(
                        "current.proposal_id = person.proposal_id "
                                + "AND current.version_number "
                                + "= person.version_number"
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

    private ProposalRowResponse proposalRow() {
        return new ProposalRowResponse(
                10L,
                "P-100",
                3,
                "Proposal title",
                "ACTIVE",
                "New",
                "Research",
                "SP-1",
                "Sponsor",
                "UNIT-1",
                "Unit",
                "PERSON-1",
                "Principal Investigator",
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null
        );
    }
}
