package edu.bu.archive.adapter.out.persistence;

import edu.bu.archive.adapter.in.web.dto.PageResponse;
import edu.bu.archive.adapter.in.web.dto.protocol.ProtocolActionResponse;
import edu.bu.archive.adapter.in.web.dto.protocol.ProtocolAmendRenewalResponse;
import edu.bu.archive.adapter.in.web.dto.protocol.ProtocolFundingResponse;
import edu.bu.archive.adapter.in.web.dto.protocol.ProtocolLocationResponse;
import edu.bu.archive.adapter.in.web.dto.protocol.ProtocolResearchAreaResponse;
import edu.bu.archive.adapter.in.web.dto.protocol.ProtocolSubmissionResponse;
import edu.bu.archive.adapter.in.web.dto.protocol.ProtocolSummaryResponse;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ProtocolArchiveRepositoryTest {

    @Test
    void firstPageUsesOneMaterializedSearchAndPreservesPagination() {
        FamilyQuery query = familyQuery(List.of(
                familyRow(51L, "000001"),
                familyRow(51L, "000002")
        ));

        PageResponse<ProtocolSummaryResponse> result =
                query.repository().findFamilies("", 0, 25);

        assertThat(query.sqlStatements()).singleElement().satisfies(sql ->
                assertThat(sql)
                .contains("WITH filtered AS MATERIALIZED")
                .contains("page_rows AS")
                .contains("total AS")
                .contains("SELECT COUNT(*) AS total_elements")
                .contains("LEFT JOIN page_rows ON TRUE")
                .contains("FROM archive.v_protocol_family family")
                .doesNotContain("archive.protocol_version version")
                .doesNotContain("archive.protocol_person person")
                .doesNotContain("archive.protocol_funding funding")
                .doesNotContain("archive.protocol_submission submission")
        );
        assertThat(result.content())
                .extracting(ProtocolSummaryResponse::protocolNumber)
                .containsExactly("000001", "000002");
        assertThat(result.totalElements()).isEqualTo(51L);
        assertThat(result.totalPages()).isEqualTo(3);
        assertThat(result.first()).isTrue();
        assertThat(result.last()).isFalse();
        verify(query.statement()).param("size", 25);
        verify(query.statement()).param("offset", 0);
    }

    @Test
    void filteredSearchUsesOneStatementAndPreservesPredicates() {
        FamilyQuery query = familyQuery(
                List.of(familyRow(1L, "000100"))
        );

        PageResponse<ProtocolSummaryResponse> result =
                query.repository().findFamilies("researcher", 0, 25);

        assertThat(query.normalizedSqlStatements())
                .singleElement()
                .satisfies(sql -> assertThat(sql)
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
                )
        );
        verify(query.statement()).param("query", "researcher");
        assertThat(result.content()).hasSize(1);
        assertThat(result.totalElements()).isEqualTo(1L);
        assertThat(result.totalPages()).isEqualTo(1);
        assertThat(result.first()).isTrue();
        assertThat(result.last()).isTrue();
    }

    @Test
    void zeroMatchesReturnsExactTotalWithoutSyntheticFamily() {
        FamilyQuery query = familyQuery(
                List.of(familyRow(0L, null))
        );

        PageResponse<ProtocolSummaryResponse> result =
                query.repository().findFamilies("missing", 0, 25);

        assertThat(result.content()).isEmpty();
        assertThat(result.totalElements()).isZero();
        assertThat(result.totalPages()).isZero();
        assertThat(result.first()).isTrue();
        assertThat(result.last()).isTrue();
    }

    @Test
    void outOfRangePageReturnsTotalWithoutSyntheticFamily() {
        FamilyQuery query = familyQuery(
                List.of(familyRow(51L, null))
        );

        PageResponse<ProtocolSummaryResponse> result =
                query.repository().findFamilies("", 3, 25);

        assertThat(result.content()).isEmpty();
        assertThat(result.totalElements()).isEqualTo(51L);
        assertThat(result.totalPages()).isEqualTo(3);
        assertThat(result.first()).isFalse();
        assertThat(result.last()).isTrue();
        verify(query.statement()).param("offset", 75);
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

    private FamilyQuery familyQuery(
            List<ProtocolArchiveRepository.FamilySearchRow> rows
    ) {
        JdbcClient jdbc = mock(JdbcClient.class);
        JdbcClient.StatementSpec statement =
                mock(JdbcClient.StatementSpec.class);
        @SuppressWarnings("unchecked")
        JdbcClient.MappedQuerySpec<
                ProtocolArchiveRepository.FamilySearchRow
        > mappedQuery = mock(JdbcClient.MappedQuerySpec.class);

        when(jdbc.sql(anyString())).thenReturn(statement);
        when(statement.param(
                anyString(),
                org.mockito.ArgumentMatchers.any()
        )).thenReturn(statement);
        when(statement.query(
                ProtocolArchiveRepository.FamilySearchRow.class
        )).thenReturn(mappedQuery);
        when(mappedQuery.list()).thenReturn(rows);

        return new FamilyQuery(
                new ProtocolArchiveRepository(jdbc),
                jdbc,
                statement
        );
    }

    private ProtocolArchiveRepository.FamilySearchRow familyRow(
            long totalElements,
            String protocolNumber
    ) {
        return new ProtocolArchiveRepository.FamilySearchRow(
                totalElements,
                protocolNumber,
                protocolNumber == null ? null : 3L,
                protocolNumber == null ? null : 100L,
                protocolNumber == null ? null : 2,
                protocolNumber == null ? null : "Protocol title",
                protocolNumber == null ? null : "Active",
                protocolNumber == null ? null : "Research",
                protocolNumber == null ? null : "Y",
                protocolNumber == null
                        ? null
                        : LocalDate.of(2027, 1, 1)
        );
    }

    private record FamilyQuery(
            ProtocolArchiveRepository repository,
            JdbcClient jdbc,
            JdbcClient.StatementSpec statement
    ) {
        List<String> sqlStatements() {
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
                    .toList();
        }

        List<String> normalizedSqlStatements() {
            return sqlStatements().stream()
                    .map(sql -> sql.replaceAll("\\s+", " "))
                    .toList();
        }
    }
}
