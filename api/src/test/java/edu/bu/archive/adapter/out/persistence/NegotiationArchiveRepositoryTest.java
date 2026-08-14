package edu.bu.archive.adapter.out.persistence;

import edu.bu.archive.adapter.in.web.dto.negotiation.NegotiationAttachmentResponse;
import edu.bu.archive.adapter.in.web.dto.negotiation.NegotiationCustomDataResponse;
import edu.bu.archive.adapter.in.web.dto.negotiation.NegotiationNotificationResponse;
import edu.bu.archive.adapter.in.web.dto.negotiation.NegotiationRowResponse;
import edu.bu.archive.adapter.in.web.dto.negotiation.NegotiationSummaryResponse;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.util.List;
import java.util.Optional;

import static edu.bu.archive.testsupport.NegotiationFixtures.negotiationRow;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
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
    void findNegotiationsPrioritizesAnExactIdOrDocumentNumberMatch() {
        JdbcClient jdbc = mock(JdbcClient.class);
        JdbcClient.StatementSpec statement =
                mock(JdbcClient.StatementSpec.class);
        @SuppressWarnings("unchecked")
        JdbcClient.MappedQuerySpec<NegotiationSummaryResponse> query =
                mock(JdbcClient.MappedQuerySpec.class);

        when(jdbc.sql(anyString())).thenReturn(statement);
        when(statement.param(anyString(), any())).thenReturn(statement);
        when(statement.query(NegotiationSummaryResponse.class))
                .thenReturn(query);
        when(query.list()).thenReturn(List.of());

        NegotiationArchiveRepository repository =
                new NegotiationArchiveRepository(jdbc);

        repository.findNegotiations("420", 25, 0);

        String sql = firstSql(jdbc);

        assertThat(sql)
                .contains(
                        "CASE WHEN CAST(negotiation_id AS TEXT) = :query"
                )
                .contains("CASE WHEN document_number = :query");
        assertThat(sql.indexOf("CASE WHEN CAST(negotiation_id AS TEXT)"))
                .isLessThan(sql.indexOf("source_update_timestamp DESC"));
    }

    @Test
    void findNegotiationsOmitsTheExactMatchCaseWhenQueryIsBlank() {
        JdbcClient jdbc = mock(JdbcClient.class);
        JdbcClient.StatementSpec statement =
                mock(JdbcClient.StatementSpec.class);
        @SuppressWarnings("unchecked")
        JdbcClient.MappedQuerySpec<NegotiationSummaryResponse> query =
                mock(JdbcClient.MappedQuerySpec.class);

        when(jdbc.sql(anyString())).thenReturn(statement);
        when(statement.param(anyString(), any())).thenReturn(statement);
        when(statement.query(NegotiationSummaryResponse.class))
                .thenReturn(query);
        when(query.list()).thenReturn(List.of());

        NegotiationArchiveRepository repository =
                new NegotiationArchiveRepository(jdbc);

        repository.findNegotiations(null, 25, 0);

        String sql = firstSql(jdbc);

        assertThat(sql).doesNotContain("CASE WHEN");
        verify(statement, org.mockito.Mockito.never())
                .param(org.mockito.ArgumentMatchers.eq("query"), any());
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

    @Test
    void findAttachmentsScopesByModuleCodeAndParentRecordId() {
        JdbcClient jdbc = mock(JdbcClient.class);
        JdbcClient.StatementSpec statement =
                mock(JdbcClient.StatementSpec.class);
        @SuppressWarnings("unchecked")
        JdbcClient.MappedQuerySpec<NegotiationAttachmentResponse> query =
                mock(JdbcClient.MappedQuerySpec.class);

        when(jdbc.sql(anyString())).thenReturn(statement);
        when(statement.param("negotiationId", 101L))
                .thenReturn(statement);
        when(statement.query(NegotiationAttachmentResponse.class))
                .thenReturn(query);
        when(query.list()).thenReturn(List.of());

        NegotiationArchiveRepository repository =
                new NegotiationArchiveRepository(jdbc);

        repository.findAttachments(101L);

        String sql = firstSql(jdbc);
        assertThat(sql)
                .contains("FROM archive.archived_attachment")
                .contains("module_code = 'NEGOTIATION'")
                .contains("parent_record_id = :negotiationId")
                .contains("source_metadata->>'activity_id'")
                .contains("source_metadata->>'source_update_user'");
        verify(statement).param("negotiationId", 101L);
    }

    @Test
    void findCustomDataJoinsTheSharedCustomAttributeLookup() {
        JdbcClient jdbc = mock(JdbcClient.class);
        JdbcClient.StatementSpec statement =
                mock(JdbcClient.StatementSpec.class);
        @SuppressWarnings("unchecked")
        JdbcClient.MappedQuerySpec<NegotiationCustomDataResponse> query =
                mock(JdbcClient.MappedQuerySpec.class);

        when(jdbc.sql(anyString())).thenReturn(statement);
        when(statement.param("negotiationId", 101L))
                .thenReturn(statement);
        when(statement.query(NegotiationCustomDataResponse.class))
                .thenReturn(query);
        when(query.list()).thenReturn(List.of());

        NegotiationArchiveRepository repository =
                new NegotiationArchiveRepository(jdbc);

        repository.findCustomData(101L);

        String sql = firstSql(jdbc);
        assertThat(sql)
                .contains("LEFT JOIN archive.custom_attribute ca")
                .contains("ca.custom_attribute_id = ncd.custom_attribute_id")
                .contains("ca.label AS label")
                .contains("ca.name AS name");
    }

    @Test
    void resolveCurrentAwardIdRequiresThePrimaryCurrentVersion() {
        JdbcClient jdbc = mock(JdbcClient.class);
        JdbcClient.StatementSpec statement =
                mock(JdbcClient.StatementSpec.class);
        @SuppressWarnings("unchecked")
        JdbcClient.MappedQuerySpec<Long> query =
                mock(JdbcClient.MappedQuerySpec.class);

        when(jdbc.sql(anyString())).thenReturn(statement);
        when(statement.param(anyString(), any())).thenReturn(statement);
        when(statement.query(Long.class)).thenReturn(query);
        when(query.optional()).thenReturn(Optional.of(555L));

        NegotiationArchiveRepository repository =
                new NegotiationArchiveRepository(jdbc);

        Optional<Long> result =
                repository.resolveCurrentAwardId("204107-00001");

        assertThat(result).contains(555L);
        assertThat(firstSql(jdbc))
                .contains("FROM archive.award_version")
                .contains("award_number = :awardNumber")
                .contains("is_primary_current = TRUE");
    }

    @Test
    void resolveCurrentProposalIdRequiresTheActiveSequence() {
        JdbcClient jdbc = mock(JdbcClient.class);
        JdbcClient.StatementSpec statement =
                mock(JdbcClient.StatementSpec.class);
        @SuppressWarnings("unchecked")
        JdbcClient.MappedQuerySpec<Long> query =
                mock(JdbcClient.MappedQuerySpec.class);

        when(jdbc.sql(anyString())).thenReturn(statement);
        when(statement.param(anyString(), any())).thenReturn(statement);
        when(statement.query(Long.class)).thenReturn(query);
        when(query.optional()).thenReturn(Optional.of(777L));

        NegotiationArchiveRepository repository =
                new NegotiationArchiveRepository(jdbc);

        Optional<Long> result =
                repository.resolveCurrentProposalId("01164319");

        assertThat(result).contains(777L);
        assertThat(firstSql(jdbc))
                .contains("FROM archive.proposal_version")
                .contains("proposal_number = :proposalNumber")
                .contains("proposal_sequence_status = 'ACTIVE'");
    }

    @Test
    void subawardExistsChecksTheSubawardTableDirectly() {
        JdbcClient jdbc = mock(JdbcClient.class);
        JdbcClient.StatementSpec statement =
                mock(JdbcClient.StatementSpec.class);
        @SuppressWarnings("unchecked")
        JdbcClient.MappedQuerySpec<Long> query =
                mock(JdbcClient.MappedQuerySpec.class);

        when(jdbc.sql(anyString())).thenReturn(statement);
        when(statement.param(anyString(), any())).thenReturn(statement);
        when(statement.query(Long.class)).thenReturn(query);
        when(query.optional()).thenReturn(Optional.of(1672L));

        NegotiationArchiveRepository repository =
                new NegotiationArchiveRepository(jdbc);

        assertThat(repository.subawardExists(1672L)).isTrue();
        assertThat(firstSql(jdbc))
                .contains("FROM archive.subaward")
                .contains("subaward_id = :subawardId");
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

}
