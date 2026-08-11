package edu.bu.archive.adapter.out.persistence;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/*
 * SQL-text-lock test, mirroring SemanticSearchRepositoryTest's and
 * AwardArchiveRepositoryTest's own established convention for this
 * module (no real database is used anywhere in the Java API test
 * suite - every repository test verifies SQL construction and bound
 * parameters, not query execution against real data).
 */
class AwardEvidenceRetrievalRepositoryTest {

    @Test
    void findNearestEvidenceScopesToTheExactAwardAndApprovedTypesWithThresholdAndOrdering() {
        JdbcClient jdbc = mock(JdbcClient.class);
        JdbcClient.StatementSpec statement = mock(JdbcClient.StatementSpec.class);
        @SuppressWarnings("unchecked")
        JdbcClient.MappedQuerySpec<AwardEvidenceRow> query =
                mock(JdbcClient.MappedQuerySpec.class);

        when(jdbc.sql(anyString())).thenReturn(statement);
        when(statement.param(anyString(), any())).thenReturn(statement);
        when(statement.query(any(RowMapper.class))).thenReturn(query);
        when(query.list()).thenReturn(List.of());

        new AwardEvidenceRetrievalRepository(jdbc).findNearestEvidence(
                "204713-00001",
                List.of("RELATED_PROPOSAL"),
                new float[]{0.1f, 0.2f},
                1.5,
                8
        );

        String sql = firstSql(jdbc);
        assertThat(sql)
                .contains("FROM archive.search_embedding")
                .contains("WHERE module = 'AWARD'")
                .contains("parent_business_identifier = :awardNumber")
                .contains("document_type IN (:documentTypes)")
                .contains("WHERE distance <= :maxDistance")
                .contains("ORDER BY distance, source_primary_key")
                .contains("LIMIT :topK");

        verify(statement).param("awardNumber", "204713-00001");
        verify(statement).param("documentTypes", List.of("RELATED_PROPOSAL"));
        verify(statement).param("maxDistance", 1.5);
        verify(statement).param("topK", 8);
    }

    @Test
    void findNearestEvidenceNeverJoinsAnotherTable() {
        // No JOIN anywhere - every row is already unique per
        // (module, document_type, exact_record_id) via V071's own
        // unique index, so a join-free query structurally cannot
        // return the same evidence row twice.
        JdbcClient jdbc = mock(JdbcClient.class);
        JdbcClient.StatementSpec statement = mock(JdbcClient.StatementSpec.class);
        @SuppressWarnings("unchecked")
        JdbcClient.MappedQuerySpec<AwardEvidenceRow> query =
                mock(JdbcClient.MappedQuerySpec.class);

        when(jdbc.sql(anyString())).thenReturn(statement);
        when(statement.param(anyString(), any())).thenReturn(statement);
        when(statement.query(any(RowMapper.class))).thenReturn(query);
        when(query.list()).thenReturn(List.of());

        new AwardEvidenceRetrievalRepository(jdbc).findNearestEvidence(
                "204713-00001", List.of("AWARD_PERSON"),
                new float[]{0.1f}, 2.0, 8
        );

        assertThat(firstSql(jdbc)).doesNotContain("JOIN");
    }

    @Test
    void findNearestEvidenceNeverReferencesAttachmentTables() {
        // Structural proof - attachment content can never be reached
        // through this repository, independent of the service-layer
        // allowlist that also rejects AWARD_ATTACHMENT.
        JdbcClient jdbc = mock(JdbcClient.class);
        JdbcClient.StatementSpec statement = mock(JdbcClient.StatementSpec.class);
        @SuppressWarnings("unchecked")
        JdbcClient.MappedQuerySpec<AwardEvidenceRow> query =
                mock(JdbcClient.MappedQuerySpec.class);

        when(jdbc.sql(anyString())).thenReturn(statement);
        when(statement.param(anyString(), any())).thenReturn(statement);
        when(statement.query(any(RowMapper.class))).thenReturn(query);
        when(query.list()).thenReturn(List.of());

        new AwardEvidenceRetrievalRepository(jdbc).findNearestEvidence(
                "204713-00001", List.of("AWARD_PERSON"),
                new float[]{0.1f}, 2.0, 8
        );

        assertThat(firstSql(jdbc))
                .doesNotContain("attachment_object")
                .doesNotContain("award_attachment");
    }

    private String firstSql(JdbcClient jdbc) {
        return org.mockito.Mockito
                .mockingDetails(jdbc)
                .getInvocations()
                .stream()
                .filter(invocation -> invocation.getMethod().getName().equals("sql"))
                .map(invocation -> (String) invocation.getArgument(0))
                .findFirst()
                .orElseThrow()
                .replaceAll("\\s+", " ");
    }
}
