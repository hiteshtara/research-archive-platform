package edu.bu.archive.adapter.out.persistence;

import edu.bu.archive.adapter.in.web.dto.protocol.ProtocolActionResponse;
import edu.bu.archive.adapter.in.web.dto.protocol.ProtocolFundingResponse;
import edu.bu.archive.adapter.in.web.dto.protocol.ProtocolLocationResponse;
import edu.bu.archive.adapter.in.web.dto.protocol.ProtocolResearchAreaResponse;
import edu.bu.archive.adapter.in.web.dto.protocol.ProtocolSubmissionResponse;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ProtocolArchiveRepositoryTest {

    @Test
    void fundingIsScopedToResolvedProtocolId() {
        JdbcClient jdbc = mock(JdbcClient.class);
        JdbcClient.StatementSpec statement =
                mock(JdbcClient.StatementSpec.class);
        @SuppressWarnings("unchecked")
        JdbcClient.MappedQuerySpec<ProtocolFundingResponse> query =
                mock(JdbcClient.MappedQuerySpec.class);

        when(jdbc.sql(anyString())).thenReturn(statement);
        when(statement.param("protocolId", 100L))
                .thenReturn(statement);
        when(statement.query(ProtocolFundingResponse.class))
                .thenReturn(query);
        when(query.list()).thenReturn(List.of());

        ProtocolArchiveRepository repository =
                new ProtocolArchiveRepository(jdbc);

        assertThat(repository.findFunding(100L)).isEmpty();
        verify(statement).param("protocolId", 100L);

        String sql = org.mockito.Mockito
                .mockingDetails(jdbc)
                .getInvocations()
                .stream()
                .filter(invocation ->
                        invocation.getMethod().getName().equals("sql")
                )
                .map(invocation ->
                        (String) invocation.getArgument(0)
                )
                .findFirst()
                .orElseThrow()
                .replaceAll("\\s+", " ");

        assertThat(sql)
                .contains("FROM archive.protocol_funding")
                .contains("WHERE protocol_id = :protocolId");
    }

    @Test
    void researchAreasAreScopedToResolvedProtocolId() {
        JdbcClient jdbc = mock(JdbcClient.class);
        JdbcClient.StatementSpec statement =
                mock(JdbcClient.StatementSpec.class);
        @SuppressWarnings("unchecked")
        JdbcClient.MappedQuerySpec<ProtocolResearchAreaResponse> query =
                mock(JdbcClient.MappedQuerySpec.class);
        when(jdbc.sql(anyString())).thenReturn(statement);
        when(statement.param("protocolId", 100L))
                .thenReturn(statement);
        when(statement.query(ProtocolResearchAreaResponse.class))
                .thenReturn(query);
        when(query.list()).thenReturn(List.of());

        ProtocolArchiveRepository repository =
                new ProtocolArchiveRepository(jdbc);

        assertThat(repository.findResearchAreas(100L)).isEmpty();
        verify(statement).param("protocolId", 100L);
    }

    @Test
    void locationsAreScopedToResolvedProtocolId() {
        JdbcClient jdbc = mock(JdbcClient.class);
        JdbcClient.StatementSpec statement =
                mock(JdbcClient.StatementSpec.class);
        @SuppressWarnings("unchecked")
        JdbcClient.MappedQuerySpec<ProtocolLocationResponse> query =
                mock(JdbcClient.MappedQuerySpec.class);
        when(jdbc.sql(anyString())).thenReturn(statement);
        when(statement.param("protocolId", 100L))
                .thenReturn(statement);
        when(statement.query(ProtocolLocationResponse.class))
                .thenReturn(query);
        when(query.list()).thenReturn(List.of());

        assertThat(
                new ProtocolArchiveRepository(jdbc).findLocations(100L)
        ).isEmpty();
        verify(statement).param("protocolId", 100L);
    }

    @Test
    void submissionsAreScopedAndDeterministicallyOrdered() {
        JdbcClient jdbc = mock(JdbcClient.class);
        JdbcClient.StatementSpec statement =
                mock(JdbcClient.StatementSpec.class);
        @SuppressWarnings("unchecked")
        JdbcClient.MappedQuerySpec<ProtocolSubmissionResponse> query =
                mock(JdbcClient.MappedQuerySpec.class);
        when(jdbc.sql(anyString())).thenReturn(statement);
        when(statement.param("protocolId", 100L))
                .thenReturn(statement);
        when(statement.query(ProtocolSubmissionResponse.class))
                .thenReturn(query);
        when(query.list()).thenReturn(List.of());

        assertThat(
                new ProtocolArchiveRepository(jdbc)
                        .findSubmissions(100L)
        ).isEmpty();

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
                .contains("FROM archive.protocol_submission")
                .contains("WHERE protocol_id = :protocolId")
                .contains(
                        "submission_date NULLS LAST, "
                                + "submission_number NULLS LAST, "
                                + "submission_id"
                );
    }

    @Test
    void actionsAreScopedAndDeterministicallyOrdered() {
        JdbcClient jdbc = mock(JdbcClient.class);
        JdbcClient.StatementSpec statement =
                mock(JdbcClient.StatementSpec.class);
        @SuppressWarnings("unchecked")
        JdbcClient.MappedQuerySpec<ProtocolActionResponse> query =
                mock(JdbcClient.MappedQuerySpec.class);
        when(jdbc.sql(anyString())).thenReturn(statement);
        when(statement.param("protocolId", 100L))
                .thenReturn(statement);
        when(statement.query(ProtocolActionResponse.class))
                .thenReturn(query);
        when(query.list()).thenReturn(List.of());

        assertThat(
                new ProtocolArchiveRepository(jdbc).findActions(100L)
        ).isEmpty();

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
                .contains("FROM archive.protocol_action")
                .contains("WHERE protocol_id = :protocolId")
                .contains("action_date NULLS LAST")
                .contains("protocol_action_id");
    }
}
