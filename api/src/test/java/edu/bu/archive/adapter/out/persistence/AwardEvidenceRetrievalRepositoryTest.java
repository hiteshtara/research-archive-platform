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

    @Test
    void unrestrictedRelatedScopeLeavesTheStatementTextUnchanged() {
        // QA H5: enforcement off and Central must issue the pre-H5 SQL.
        JdbcClient before = stubbedJdbc();
        new AwardEvidenceRetrievalRepository(before).findNearestEvidence(
                "204713-00001", List.of("RELATED_PROPOSAL"), new float[]{0.1f}, 2.0, 8);
        JdbcClient after = stubbedJdbc();
        new AwardEvidenceRetrievalRepository(after).findNearestEvidence(
                "204713-00001", List.of("RELATED_PROPOSAL"), new float[]{0.1f}, 2.0, 8,
                RelatedEvidenceScope.UNRESTRICTED);

        assertThat(rawSql(after)).isEqualTo(rawSql(before));
    }

    @Test
    void restrictedRelatedScopeIsAppliedInsideTheCandidateSetBeforeRankingAndLimit() {
        JdbcClient jdbc = stubbedJdbc();
        JdbcClient.StatementSpec statement = jdbc.sql("");
        edu.bu.archive.application.authorization.SqlFragment unit =
                new edu.bu.archive.application.authorization.SqlFragment(
                        " AND {alias}.lead_unit_number IN (:az_units) ",
                        java.util.Map.of("az_units", List.of("7777")));

        new AwardEvidenceRetrievalRepository(jdbc).findNearestEvidence(
                "204713-00001", List.of("RELATED_PROPOSAL"), new float[]{0.1f}, 2.0, 8,
                new RelatedEvidenceScope(
                        new edu.bu.archive.application.authorization.SqlFragment(
                                unit.sql().replace("{alias}", RelatedEvidenceScope.PROPOSAL_ALIAS), unit.params()),
                        edu.bu.archive.application.authorization.SqlFragment.DENY_ALL,
                        edu.bu.archive.application.authorization.SqlFragment.DENY_ALL));

        String sql = rawSql(jdbc).replaceAll("\\s+", " ");
        int predicate = sql.indexOf("ev_proposal.lead_unit_number IN (:ev_p_az_units)");
        int endOfCandidates = sql.indexOf(") SELECT document_type");
        assertThat(predicate).as("proposal predicate present").isPositive();
        assertThat(predicate).as("predicate inside the candidates CTE").isLessThan(endOfCandidates);
        assertThat(sql.indexOf("ORDER BY distance")).isGreaterThan(endOfCandidates);
        assertThat(sql)
                .contains("ev_afp.award_funding_proposal_id = search_embedding.exact_record_id")
                .contains("ev_negotiation.negotiation_id = search_embedding.exact_record_id")
                .contains("ev_funding.subaward_funding_source_id = search_embedding.exact_record_id")
                .contains("AND FALSE");
        verify(statement).param("ev_p_az_units", List.of("7777"));
    }

    private static JdbcClient stubbedJdbc() {
        JdbcClient jdbc = mock(JdbcClient.class);
        JdbcClient.StatementSpec statement = mock(JdbcClient.StatementSpec.class);
        @SuppressWarnings("unchecked")
        JdbcClient.MappedQuerySpec<AwardEvidenceRow> query =
                mock(JdbcClient.MappedQuerySpec.class);
        when(jdbc.sql(anyString())).thenReturn(statement);
        when(statement.param(anyString(), any())).thenReturn(statement);
        when(statement.query(any(RowMapper.class))).thenReturn(query);
        when(query.list()).thenReturn(List.of());
        return jdbc;
    }

    private static String rawSql(JdbcClient jdbc) {
        return org.mockito.Mockito
                .mockingDetails(jdbc)
                .getInvocations()
                .stream()
                .filter(invocation -> invocation.getMethod().getName().equals("sql"))
                .map(invocation -> (String) invocation.getArgument(0))
                .reduce((first, last) -> last)
                .orElseThrow();
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
