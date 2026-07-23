package edu.bu.archive.adapter.out.persistence;

import edu.bu.archive.adapter.in.web.dto.protocol.ProtocolFundingResponse;

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
}
