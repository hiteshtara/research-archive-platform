package edu.bu.archive.adapter.out.persistence;

import edu.bu.archive.adapter.in.web.dto.negotiation.NegotiationNotificationResponse;
import edu.bu.archive.adapter.in.web.dto.negotiation.NegotiationRowResponse;
import edu.bu.archive.adapter.in.web.dto.negotiation.NegotiationSummaryResponse;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class NegotiationArchiveRepositoryTest {

    @Test
    void findByIdMapsTheNegotiationRow() {
        JdbcClient jdbc = mock(JdbcClient.class);
        JdbcClient.StatementSpec statement =
                mock(JdbcClient.StatementSpec.class);
        @SuppressWarnings("unchecked")
        JdbcClient.MappedQuerySpec<NegotiationRowResponse> query =
                mock(JdbcClient.MappedQuerySpec.class);
        NegotiationRowResponse expected = negotiationRow();

        when(jdbc.sql(anyString())).thenReturn(statement);
        when(statement.param("negotiationId", 101L))
                .thenReturn(statement);
        when(statement.query(NegotiationRowResponse.class))
                .thenReturn(query);
        when(query.optional()).thenReturn(Optional.of(expected));

        NegotiationArchiveRepository repository =
                new NegotiationArchiveRepository(jdbc);

        Optional<NegotiationRowResponse> result =
                repository.findById(101L);

        assertThat(result).contains(expected);
        verify(statement).param("negotiationId", 101L);
    }

    @Test
    void findNegotiationsUsesArchiveFieldsWithoutAssociationMapping() {
        JdbcClient jdbc = mock(JdbcClient.class);
        JdbcClient.StatementSpec statement =
                mock(JdbcClient.StatementSpec.class);
        @SuppressWarnings("unchecked")
        JdbcClient.MappedQuerySpec<NegotiationSummaryResponse> query =
                mock(JdbcClient.MappedQuerySpec.class);

        when(jdbc.sql(anyString())).thenReturn(statement);
        when(statement.param(anyString(),
                org.mockito.ArgumentMatchers.any()))
                .thenReturn(statement);
        when(statement.query(NegotiationSummaryResponse.class))
                .thenReturn(query);
        when(query.list()).thenReturn(List.of());

        NegotiationArchiveRepository repository =
                new NegotiationArchiveRepository(jdbc);

        repository.findNegotiations("award", 25, 50);

        String sql = firstSql(jdbc);

        assertThat(sql)
                .contains("FROM archive.negotiation")
                .contains("negotiation_association_type_id")
                .contains("associated_document_id")
                .doesNotContain("archive.proposal")
                .doesNotContain("archive.award")
                .doesNotContain("archive.subaward");
        verify(statement).param("query", "award");
        verify(statement).param("limit", 25);
        verify(statement).param("offset", 50);
    }

    @Test
    void findNotificationsUsesTheVerifiedParentColumn() {
        JdbcClient jdbc = mock(JdbcClient.class);
        JdbcClient.StatementSpec statement =
                mock(JdbcClient.StatementSpec.class);
        @SuppressWarnings("unchecked")
        JdbcClient.MappedQuerySpec<NegotiationNotificationResponse> query =
                mock(JdbcClient.MappedQuerySpec.class);

        when(jdbc.sql(anyString())).thenReturn(statement);
        when(statement.param("negotiationId", 101L))
                .thenReturn(statement);
        when(statement.query(NegotiationNotificationResponse.class))
                .thenReturn(query);
        when(query.list()).thenReturn(List.of());

        NegotiationArchiveRepository repository =
                new NegotiationArchiveRepository(jdbc);

        List<NegotiationNotificationResponse> result =
                repository.findNotifications(101L);

        assertThat(result).isEmpty();
        assertThat(firstSql(jdbc))
                .contains("FROM archive.negotiation_notification")
                .contains("owning_document_id_fk = :negotiationId");
    }

    private String firstSql(JdbcClient jdbc) {
        return org.mockito.Mockito
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
    }

    private NegotiationRowResponse negotiationRow() {
        return new NegotiationRowResponse(
                101L,
                "DOC-101",
                1L,
                "ACTIVE",
                "Active",
                2L,
                "AGREEMENT",
                "Agreement",
                3L,
                "AWARD",
                "Award",
                "PERSON-1",
                "Negotiator",
                null,
                null,
                null,
                null,
                "00001234",
                null,
                null,
                1L,
                "OBJECT-1",
                null,
                null,
                1L,
                "DOCUMENT-OBJECT-1"
        );
    }
}
