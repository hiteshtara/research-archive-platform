package edu.bu.archive.adapter.out.persistence;

import edu.bu.archive.adapter.in.web.dto.protocol.ProtocolActionResponse;
import edu.bu.archive.adapter.in.web.dto.protocol.ProtocolAmendRenewalResponse;
import edu.bu.archive.adapter.in.web.dto.protocol.ProtocolFundingResponse;
import edu.bu.archive.adapter.in.web.dto.protocol.ProtocolLocationResponse;
import edu.bu.archive.adapter.in.web.dto.protocol.ProtocolResearchAreaResponse;
import edu.bu.archive.adapter.in.web.dto.protocol.ProtocolSubmissionResponse;
import edu.bu.archive.adapter.in.web.dto.protocol.ProtocolSummaryResponse;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ProtocolArchiveRepositoryTest {

    @Test
    void unfilteredFamilyLoadSkipsRelatedObjectSearches() {
        JdbcClient jdbc = mock(JdbcClient.class);
        JdbcClient.StatementSpec statement =
                mock(JdbcClient.StatementSpec.class);
        @SuppressWarnings("unchecked")
        JdbcClient.MappedQuerySpec<Long> countQuery =
                mock(JdbcClient.MappedQuerySpec.class);
        @SuppressWarnings("unchecked")
        JdbcClient.MappedQuerySpec<ProtocolSummaryResponse> contentQuery =
                mock(JdbcClient.MappedQuerySpec.class);

        when(jdbc.sql(anyString())).thenReturn(statement);
        when(statement.param(
                anyString(),
                org.mockito.ArgumentMatchers.any()
        )).thenReturn(statement);
        when(statement.query(Long.class)).thenReturn(countQuery);
        when(countQuery.single()).thenReturn(0L);
        when(statement.query(ProtocolSummaryResponse.class))
                .thenReturn(contentQuery);
        when(contentQuery.list()).thenReturn(List.of());

        new ProtocolArchiveRepository(jdbc).findFamilies("", 0, 25);

        List<String> sqlStatements = org.mockito.Mockito
                .mockingDetails(jdbc)
                .getInvocations()
                .stream()
                .filter(invocation ->
                        invocation.getMethod().getName().equals("sql")
                )
                .map(invocation -> (String) invocation.getArgument(0))
                .toList();

        assertThat(sqlStatements).hasSize(2);
        assertThat(sqlStatements).allSatisfy(sql -> assertThat(sql)
                .contains("FROM archive.v_protocol_family family")
                .doesNotContain("archive.protocol_version version")
                .doesNotContain("archive.protocol_person person")
                .doesNotContain("archive.protocol_funding funding")
                .doesNotContain("archive.protocol_submission submission"));
    }

    @Test
    void familySearchIncludesProtocolAndRelatedArchiveObjects() {
        JdbcClient jdbc = mock(JdbcClient.class);
        JdbcClient.StatementSpec statement =
                mock(JdbcClient.StatementSpec.class);
        @SuppressWarnings("unchecked")
        JdbcClient.MappedQuerySpec<Long> countQuery =
                mock(JdbcClient.MappedQuerySpec.class);
        @SuppressWarnings("unchecked")
        JdbcClient.MappedQuerySpec<ProtocolSummaryResponse> contentQuery =
                mock(JdbcClient.MappedQuerySpec.class);

        when(jdbc.sql(anyString())).thenReturn(statement);
        when(statement.param(
                anyString(),
                org.mockito.ArgumentMatchers.any()
        )).thenReturn(statement);
        when(statement.query(Long.class)).thenReturn(countQuery);
        when(countQuery.single()).thenReturn(0L);
        when(statement.query(ProtocolSummaryResponse.class))
                .thenReturn(contentQuery);
        when(contentQuery.list()).thenReturn(List.of());

        new ProtocolArchiveRepository(jdbc)
                .findFamilies("researcher", 0, 25);

        List<String> sqlStatements = org.mockito.Mockito
                .mockingDetails(jdbc)
                .getInvocations()
                .stream()
                .filter(invocation ->
                        invocation.getMethod().getName().equals("sql")
                )
                .map(invocation -> (String) invocation.getArgument(0))
                .map(sql -> sql.replaceAll("\\s+", " "))
                .toList();

        assertThat(sqlStatements).hasSize(2);
        assertThat(sqlStatements).allSatisfy(sql -> assertThat(sql)
                .contains("FROM archive.v_protocol_family family")
                .contains("FROM archive.protocol_version version")
                .contains("version.document_number")
                .contains("FROM archive.protocol_person person")
                .contains("person.person_name")
                .contains("FROM archive.protocol_unit unit_row")
                .contains("FROM archive.protocol_funding funding")
                .contains("funding.funding_source_name")
                .contains(
                        "FROM archive.protocol_research_area research_area"
                )
                .contains("FROM archive.protocol_location location")
                .contains("FROM archive.protocol_submission submission")
                .contains("FROM archive.protocol_action action")
                .contains(
                        "FROM archive.protocol_amend_renewal amend_renewal"
                ));
        verify(statement, times(2)).param("query", "researcher");
    }

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

    @Test
    void amendRenewalsAreScopedAndDeterministicallyOrdered() {
        JdbcClient jdbc = mock(JdbcClient.class);
        JdbcClient.StatementSpec statement =
                mock(JdbcClient.StatementSpec.class);
        @SuppressWarnings("unchecked")
        JdbcClient.MappedQuerySpec<ProtocolAmendRenewalResponse> query =
                mock(JdbcClient.MappedQuerySpec.class);
        when(jdbc.sql(anyString())).thenReturn(statement);
        when(statement.param("protocolId", 100L))
                .thenReturn(statement);
        when(statement.query(ProtocolAmendRenewalResponse.class))
                .thenReturn(query);
        when(query.list()).thenReturn(List.of());

        assertThat(
                new ProtocolArchiveRepository(jdbc)
                        .findAmendRenewals(100L)
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
                .contains("FROM archive.protocol_amend_renewal")
                .contains("WHERE protocol_id = :protocolId")
                .contains("date_created NULLS LAST")
                .contains("proto_amend_renewal_id");
    }
}
