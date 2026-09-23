package edu.bu.archive.adapter.out.persistence;

import edu.bu.archive.adapter.in.web.dto.negotiation.NegotiationAttachmentResponse;
import edu.bu.archive.adapter.in.web.dto.negotiation.NegotiationCustomDataResponse;
import edu.bu.archive.adapter.in.web.dto.negotiation.NegotiationNotificationResponse;
import edu.bu.archive.adapter.in.web.dto.negotiation.NegotiationRowResponse;
import edu.bu.archive.adapter.in.web.dto.negotiation.NegotiationSummaryResponse;
import edu.bu.archive.application.negotiation.NegotiationSearchFilters;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.util.List;
import java.util.Optional;

import static edu.bu.archive.testsupport.NegotiationFixtures.negotiationRow;
import static edu.bu.archive.testsupport.NegotiationFixtures.negotiationRow420;
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

    /*
     * Real fixture: negotiation_id=420, associated_document_id="419" -
     * two different Oracle columns/values, live-verified 2026-08-14.
     * findById must look up by negotiation_id only - the SQL text must
     * never filter on associated_document_id, and requesting 420 must
     * bind exactly 420, never the coincidentally-close 419.
     */
    @Test
    void findByIdLooksUpByNegotiationIdNeverAssociatedDocumentId() {
        JdbcClient jdbc = mock(JdbcClient.class);
        JdbcClient.StatementSpec statement =
                mock(JdbcClient.StatementSpec.class);
        @SuppressWarnings("unchecked")
        JdbcClient.MappedQuerySpec<NegotiationRowResponse> query =
                mock(JdbcClient.MappedQuerySpec.class);
        NegotiationRowResponse expected = negotiationRow420();

        when(jdbc.sql(anyString())).thenReturn(statement);
        when(statement.param("negotiationId", 420L))
                .thenReturn(statement);
        when(statement.query(NegotiationRowResponse.class))
                .thenReturn(query);
        when(query.optional()).thenReturn(Optional.of(expected));

        NegotiationArchiveRepository repository =
                new NegotiationArchiveRepository(jdbc);

        Optional<NegotiationRowResponse> result =
                repository.findById(420L);

        assertThat(result).contains(expected);
        assertThat(result.orElseThrow().negotiationId()).isEqualTo(420L);
        assertThat(result.orElseThrow().associatedDocumentId())
                .isEqualTo("419");
        assertThat(result.orElseThrow().negotiationId())
                .isNotEqualTo(
                        Long.parseLong(
                                result.orElseThrow().associatedDocumentId()
                        )
                );

        String sql = firstSql(jdbc);
        // The predicate is now table-qualified because the query LEFT
        // JOINs archive.negotiation_search_attribute for the resolved
        // workspace attributes. The guard is unchanged in substance:
        // look up by negotiation_id, never by associated_document_id.
        assertThat(sql)
                .contains("WHERE n.negotiation_id = :negotiationId")
                .doesNotContain("associated_document_id = :negotiationId");
        // And the join must stay a plain key join - no resolver here.
        // Comments are stripped first: the SQL's own header explains why
        // there is no LATERAL, and matching that prose would assert on
        // the comment rather than on the statement.
        String statementOnly = sql.replaceAll("(?s)/\\*.*?\\*/", " ");
        assertThat(statementOnly)
                .contains("LEFT JOIN archive.negotiation_search_attribute a")
                .contains("ON a.negotiation_id = n.negotiation_id")
                .doesNotContain("LATERAL")
                .doesNotContain("award_version")
                .doesNotContain("negotiation_unassociated_detail");
        verify(statement).param("negotiationId", 420L);
        verify(statement, org.mockito.Mockito.never())
                .param("negotiationId", 419L);
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

        repository.findNegotiations(
                NegotiationSearchFilters.ofQuery("award"), 25, 50);

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

        repository.findNegotiations(
                NegotiationSearchFilters.ofQuery("420"), 25, 0);

        String sql = firstSql(jdbc);

        assertThat(sql)
                .contains(
                        "CASE WHEN CAST(n.negotiation_id AS TEXT) = :query"
                )
                .contains("CASE WHEN n.document_number = :query");
        assertThat(sql.indexOf("CASE WHEN CAST(n.negotiation_id AS TEXT)"))
                .isLessThan(sql.indexOf("n.source_update_timestamp DESC"));
    }

    /*
     * The blank-query contract changed with structured filtering, and
     * this test now pins the new one rather than the old SQL shape.
     *
     * Previously a blank query omitted the exact-match CASE from the
     * SQL entirely and never bound :query. Now a single statement
     * serves every filter combination, so the CASE is always present
     * and :query is always bound - as null. That is not a behavioural
     * regression: a null :query makes both CASE tests evaluate to NULL,
     * which falls to ELSE 1 for every row, so no row is prioritized and
     * the ordering falls through to source_update_timestamp exactly as
     * before. Binding it is required, not optional: JdbcClient rejects
     * a statement whose named parameter was never supplied.
     *
     * The real-schema integration test covers that this actually
     * executes against PostgreSQL with a null query.
     */
    @Test
    void findNegotiationsBindsANullQueryRatherThanOmittingIt() {
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

        repository.findNegotiations(
                NegotiationSearchFilters.none(), 25, 0);

        String sql = firstSql(jdbc);

        // Present, but inert for a null :query.
        assertThat(sql).contains("CASE WHEN");
        // Bound as null, so no row is prioritized and JdbcClient is happy.
        verify(statement).param("query", null);
        // A blank filter must never become a condition.
        verify(statement).param("status", null);
        verify(statement).param("principalInvestigator", null);
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
                .contains("source_metadata->>'source_update_user'")
                .contains("source_attachment_id AS oracle_attachment_id")
                .contains("source_file_id AS oracle_file_id")
                .contains("description")
                .doesNotContain("sha256");
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
