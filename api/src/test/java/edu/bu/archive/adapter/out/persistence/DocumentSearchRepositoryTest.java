package edu.bu.archive.adapter.out.persistence;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DocumentSearchRepositoryTest {

    private JdbcClient jdbc;
    private JdbcClient.StatementSpec statement;

    private DocumentSearchRepository newRepository() {
        jdbc = mock(JdbcClient.class);
        statement = mock(JdbcClient.StatementSpec.class);
        when(jdbc.sql(anyString())).thenReturn(statement);
        when(statement.param(anyString(), org.mockito.ArgumentMatchers.any()))
                .thenReturn(statement);
        return new DocumentSearchRepository(jdbc);
    }

    private String capturedSql() {
        return org.mockito.Mockito.mockingDetails(jdbc)
                .getInvocations()
                .stream()
                .filter(invocation -> invocation.getMethod().getName().equals("sql"))
                .map(invocation -> (String) invocation.getArgument(0))
                .findFirst()
                .orElseThrow()
                .replaceAll("\\s+", " ");
    }

    @Test
    void searchUsesTheFixedFiveModuleUnionNeverConstructedFromInput() {
        DocumentSearchRepository repository = newRepository();
        @SuppressWarnings("unchecked")
        JdbcClient.MappedQuerySpec<DocumentSearchRow> query =
                mock(JdbcClient.MappedQuerySpec.class);
        when(statement.query(DocumentSearchRow.class)).thenReturn(query);
        when(query.list()).thenReturn(List.of());

        repository.search(
                "", "%%", "", "", "%%", "", "%%", "", "%%", 25, 0
        );

        String sql = capturedSql();
        assertThat(sql)
                .contains("'AWARD' AS module")
                .contains("FROM archive.award_version av")
                .contains("'PROPOSAL'")
                .contains("FROM archive.proposal_version pv")
                .contains("'NEGOTIATION'")
                .contains("FROM archive.negotiation n")
                .contains("'SUBAWARD'")
                .contains("FROM archive.subaward s")
                .contains("'IRB'")
                .contains("FROM archive.irb_protocol_version ipv")
                .contains("document_number ILIKE :documentNumberPattern")
                .contains("module = :module")
                .doesNotContain("archive.award_budget")
                .doesNotContain("archive.time_and_money_document")
                .doesNotContain("archive.pending_transaction")
                .doesNotContain("archive.award_transmission")
                .doesNotContain("archive.award_attachment")
                .doesNotContain("archive.attachment_object");

        verify(statement).param("limit", 25);
        verify(statement).param("offset", 0);
    }

    @Test
    void countUsesTheSameFixedUnionAndFiltersWithNoLimitOffset() {
        DocumentSearchRepository repository = newRepository();
        @SuppressWarnings("unchecked")
        JdbcClient.MappedQuerySpec<Long> query =
                mock(JdbcClient.MappedQuerySpec.class);
        when(statement.query(Long.class)).thenReturn(query);
        when(query.single()).thenReturn(42L);

        long result = repository.count(
                "451699", "%451699%", "AWARD", "", "%%", "", "%%", "", "%%"
        );

        assertThat(result).isEqualTo(42L);
        String sql = capturedSql();
        assertThat(sql)
                .contains("SELECT COUNT(*) FROM documents")
                .doesNotContain("LIMIT")
                .doesNotContain("OFFSET");
    }

    @Test
    void countReturnsZeroWhenQuerySingleReturnsNull() {
        DocumentSearchRepository repository = newRepository();
        @SuppressWarnings("unchecked")
        JdbcClient.MappedQuerySpec<Long> query =
                mock(JdbcClient.MappedQuerySpec.class);
        when(statement.query(Long.class)).thenReturn(query);
        when(query.single()).thenReturn(null);

        assertThat(
                repository.count("", "%%", "", "", "%%", "", "%%", "", "%%")
        ).isZero();
    }
}
