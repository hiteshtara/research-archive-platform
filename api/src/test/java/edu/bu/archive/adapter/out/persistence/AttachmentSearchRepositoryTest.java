package edu.bu.archive.adapter.out.persistence;

import edu.bu.archive.adapter.in.web.dto.attachment.AttachmentSearchRow;
import edu.bu.archive.adapter.in.web.dto.attachment.MixedAttachmentSearchRow;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AttachmentSearchRepositoryTest {

    // --- searchProposalAttachments/countSearchProposalAttachments ---

    @Test
    void searchProposalAttachmentsMatchesOnExactProposalNumber() {
        JdbcClient jdbc = mock(JdbcClient.class);
        JdbcClient.StatementSpec statement = mock(JdbcClient.StatementSpec.class);
        @SuppressWarnings("unchecked")
        JdbcClient.MappedQuerySpec<AttachmentSearchRow> query =
                mock(JdbcClient.MappedQuerySpec.class);

        when(jdbc.sql(anyString())).thenReturn(statement);
        when(statement.param(anyString(), any())).thenReturn(statement);
        when(statement.query(AttachmentSearchRow.class)).thenReturn(query);
        when(query.list()).thenReturn(List.of());

        new AttachmentSearchRepository(jdbc).searchProposalAttachments(
                "2975", "", null, null, "all", "ORDER BY pv.proposal_number\n", 25, 0
        );

        assertThat(firstSql(jdbc))
                .contains(":recordNumber = '' OR UPPER(pv.proposal_number) = UPPER(:recordNumber)")
                .contains("FROM archive.proposal_attachment pa")
                .contains("JOIN archive.proposal_version pv ON pv.proposal_id = pa.proposal_id");
        verifyParam(statement, "recordNumber", "2975");
    }

    @Test
    void searchProposalAttachmentsMatchesOnExactWorkflowDocumentNumber() {
        JdbcClient jdbc = mock(JdbcClient.class);
        JdbcClient.StatementSpec statement = mock(JdbcClient.StatementSpec.class);
        @SuppressWarnings("unchecked")
        JdbcClient.MappedQuerySpec<AttachmentSearchRow> query =
                mock(JdbcClient.MappedQuerySpec.class);

        when(jdbc.sql(anyString())).thenReturn(statement);
        when(statement.param(anyString(), any())).thenReturn(statement);
        when(statement.query(AttachmentSearchRow.class)).thenReturn(query);
        when(query.list()).thenReturn(List.of());

        new AttachmentSearchRepository(jdbc).searchProposalAttachments(
                "", "879423", null, null, "all", "ORDER BY pv.proposal_number\n", 25, 0
        );

        assertThat(firstSql(jdbc))
                .contains(":documentNumber = '' OR UPPER(pv.document_number) = UPPER(:documentNumber)")
                .doesNotContain("pa.document_number");
        verifyParam(statement, "documentNumber", "879423");
    }

    @Test
    void searchProposalAttachmentsExactProposalIdFilterUsesTheNullSafeCast() {
        JdbcClient jdbc = mock(JdbcClient.class);
        JdbcClient.StatementSpec statement = mock(JdbcClient.StatementSpec.class);
        @SuppressWarnings("unchecked")
        JdbcClient.MappedQuerySpec<AttachmentSearchRow> query =
                mock(JdbcClient.MappedQuerySpec.class);

        when(jdbc.sql(anyString())).thenReturn(statement);
        when(statement.param(anyString(), any())).thenReturn(statement);
        when(statement.query(AttachmentSearchRow.class)).thenReturn(query);
        when(query.list()).thenReturn(List.of());

        new AttachmentSearchRepository(jdbc).searchProposalAttachments(
                "", "", 7125L, null, "all", "ORDER BY pv.proposal_number\n", 25, 0
        );

        assertThat(firstSql(jdbc))
                .contains("CAST(:recordId AS BIGINT) IS NULL OR pv.proposal_id = :recordId");
        verifyParam(statement, "recordId", 7125L);
    }

    @Test
    void searchProposalAttachmentsMatchesOnExactAttachmentId() {
        JdbcClient jdbc = mock(JdbcClient.class);
        JdbcClient.StatementSpec statement = mock(JdbcClient.StatementSpec.class);
        @SuppressWarnings("unchecked")
        JdbcClient.MappedQuerySpec<AttachmentSearchRow> query =
                mock(JdbcClient.MappedQuerySpec.class);

        when(jdbc.sql(anyString())).thenReturn(statement);
        when(statement.param(anyString(), any())).thenReturn(statement);
        when(statement.query(AttachmentSearchRow.class)).thenReturn(query);
        when(query.list()).thenReturn(List.of());

        new AttachmentSearchRepository(jdbc).searchProposalAttachments(
                "", "", null, 501508L, "all", "ORDER BY pv.proposal_number\n", 25, 0
        );

        assertThat(firstSql(jdbc))
                .contains("CAST(:attachmentId AS BIGINT) IS NULL OR pa.proposal_attachment_id = :attachmentId");
        verifyParam(statement, "attachmentId", 501508L);
    }

    @Test
    void searchProposalAttachmentsCombinesEveryFilterWithAndNeverOr() {
        JdbcClient jdbc = mock(JdbcClient.class);
        JdbcClient.StatementSpec statement = mock(JdbcClient.StatementSpec.class);
        @SuppressWarnings("unchecked")
        JdbcClient.MappedQuerySpec<AttachmentSearchRow> query =
                mock(JdbcClient.MappedQuerySpec.class);

        when(jdbc.sql(anyString())).thenReturn(statement);
        when(statement.param(anyString(), any())).thenReturn(statement);
        when(statement.query(AttachmentSearchRow.class)).thenReturn(query);
        when(query.list()).thenReturn(List.of());

        new AttachmentSearchRepository(jdbc).searchProposalAttachments(
                "2975", "879423", 7125L, null, "current", "ORDER BY pv.proposal_number\n", 25, 0
        );

        String sql = firstSql(jdbc);
        assertThat(sql.split("WHERE", 2)[1]).doesNotContain("\nOR ");
        verifyParam(statement, "recordNumber", "2975");
        verifyParam(statement, "documentNumber", "879423");
        verifyParam(statement, "recordId", 7125L);
        verifyParam(statement, "versionFilter", "current");
    }

    @Test
    void searchProposalAttachmentsVersionFilterSupportsAllCurrentAndHistorical() {
        JdbcClient jdbc = mock(JdbcClient.class);
        JdbcClient.StatementSpec statement = mock(JdbcClient.StatementSpec.class);
        @SuppressWarnings("unchecked")
        JdbcClient.MappedQuerySpec<AttachmentSearchRow> query =
                mock(JdbcClient.MappedQuerySpec.class);

        when(jdbc.sql(anyString())).thenReturn(statement);
        when(statement.param(anyString(), any())).thenReturn(statement);
        when(statement.query(AttachmentSearchRow.class)).thenReturn(query);
        when(query.list()).thenReturn(List.of());

        new AttachmentSearchRepository(jdbc).searchProposalAttachments(
                "", "", 7125L, null, "all", "ORDER BY pv.proposal_number\n", 25, 0
        );

        assertThat(firstSql(jdbc))
                .contains(":versionFilter = 'all'")
                .contains(":versionFilter = 'current' AND pv.proposal_id = current_pv.proposal_id")
                .contains(":versionFilter = 'historical' AND pv.proposal_id != current_pv.proposal_id");
    }

    @Test
    void searchProposalAttachmentsResolvesPiFromProposalVersionDirectlyNeverJoiningProposalPerson() {
        // principal_investigator_name is already denormalized onto
        // proposal_version at Oracle extraction time - joining
        // proposal_person here would risk multiplying rows for no
        // reason; this proves the query never does.
        JdbcClient jdbc = mock(JdbcClient.class);
        JdbcClient.StatementSpec statement = mock(JdbcClient.StatementSpec.class);
        @SuppressWarnings("unchecked")
        JdbcClient.MappedQuerySpec<AttachmentSearchRow> query =
                mock(JdbcClient.MappedQuerySpec.class);

        when(jdbc.sql(anyString())).thenReturn(statement);
        when(statement.param(anyString(), any())).thenReturn(statement);
        when(statement.query(AttachmentSearchRow.class)).thenReturn(query);
        when(query.list()).thenReturn(List.of());

        new AttachmentSearchRepository(jdbc).searchProposalAttachments(
                "2975", "", null, null, "all", "ORDER BY pv.proposal_number\n", 25, 0
        );

        assertThat(firstSql(jdbc))
                .contains("pv.principal_investigator_name AS principal_investigator")
                .doesNotContain("proposal_person");
    }

    @Test
    void searchProposalAttachmentsCurrentVersionResolutionIsLateralAndCappedAtOneRow() {
        JdbcClient jdbc = mock(JdbcClient.class);
        JdbcClient.StatementSpec statement = mock(JdbcClient.StatementSpec.class);
        @SuppressWarnings("unchecked")
        JdbcClient.MappedQuerySpec<AttachmentSearchRow> query =
                mock(JdbcClient.MappedQuerySpec.class);

        when(jdbc.sql(anyString())).thenReturn(statement);
        when(statement.param(anyString(), any())).thenReturn(statement);
        when(statement.query(AttachmentSearchRow.class)).thenReturn(query);
        when(query.list()).thenReturn(List.of());

        new AttachmentSearchRepository(jdbc).searchProposalAttachments(
                "2975", "", null, null, "all", "ORDER BY pv.proposal_number\n", 25, 0
        );

        assertThat(firstSql(jdbc))
                .contains("LEFT JOIN LATERAL (")
                .contains("FROM archive.proposal_version pv2")
                .contains("version_number DESC")
                .contains("source_update_timestamp DESC NULLS LAST")
                .contains("proposal_id DESC")
                .contains("LIMIT 1")
                .contains(") current_pv ON TRUE");
    }

    @Test
    void searchProposalAttachmentsNeverSelectsS3OrFileDataIdColumns() {
        JdbcClient jdbc = mock(JdbcClient.class);
        JdbcClient.StatementSpec statement = mock(JdbcClient.StatementSpec.class);
        @SuppressWarnings("unchecked")
        JdbcClient.MappedQuerySpec<AttachmentSearchRow> query =
                mock(JdbcClient.MappedQuerySpec.class);

        when(jdbc.sql(anyString())).thenReturn(statement);
        when(statement.param(anyString(), any())).thenReturn(statement);
        when(statement.query(AttachmentSearchRow.class)).thenReturn(query);
        when(query.list()).thenReturn(List.of());

        new AttachmentSearchRepository(jdbc).searchProposalAttachments(
                "2975", "", null, null, "all", "ORDER BY pv.proposal_number\n", 25, 0
        );

        String sql = firstSql(jdbc);
        assertThat(sql)
                .doesNotContain("s3_bucket AS")
                .doesNotContain("AS s3_bucket")
                .doesNotContain("object_key AS")
                .doesNotContain("AS object_key")
                .doesNotContain("file_data_id")
                .doesNotContain("checksum")
                .doesNotContain("error_message");
    }

    @Test
    void searchProposalAttachmentsAlwaysReturnsNullFileId() {
        JdbcClient jdbc = mock(JdbcClient.class);
        JdbcClient.StatementSpec statement = mock(JdbcClient.StatementSpec.class);
        @SuppressWarnings("unchecked")
        JdbcClient.MappedQuerySpec<AttachmentSearchRow> query =
                mock(JdbcClient.MappedQuerySpec.class);

        when(jdbc.sql(anyString())).thenReturn(statement);
        when(statement.param(anyString(), any())).thenReturn(statement);
        when(statement.query(AttachmentSearchRow.class)).thenReturn(query);
        when(query.list()).thenReturn(List.of());

        new AttachmentSearchRepository(jdbc).searchProposalAttachments(
                "2975", "", null, null, "all", "ORDER BY pv.proposal_number\n", 25, 0
        );

        assertThat(firstSql(jdbc)).contains("CAST(NULL AS BIGINT) AS file_id");
    }

    @Test
    void searchProposalAttachmentsSelectsFromProposalAttachmentPreservingOneRowPerRelationship() {
        JdbcClient jdbc = mock(JdbcClient.class);
        JdbcClient.StatementSpec statement = mock(JdbcClient.StatementSpec.class);
        @SuppressWarnings("unchecked")
        JdbcClient.MappedQuerySpec<AttachmentSearchRow> query =
                mock(JdbcClient.MappedQuerySpec.class);

        when(jdbc.sql(anyString())).thenReturn(statement);
        when(statement.param(anyString(), any())).thenReturn(statement);
        when(statement.query(AttachmentSearchRow.class)).thenReturn(query);
        when(query.list()).thenReturn(List.of());

        new AttachmentSearchRepository(jdbc).searchProposalAttachments(
                "2975", "", null, null, "all", "ORDER BY pv.proposal_number\n", 25, 0
        );

        assertThat(firstSql(jdbc)).contains("FROM archive.proposal_attachment pa");
    }

    @Test
    void searchProposalAttachmentsOrderingAndPaginationAreStableAndDeterministic() {
        JdbcClient jdbc = mock(JdbcClient.class);
        JdbcClient.StatementSpec statement = mock(JdbcClient.StatementSpec.class);
        @SuppressWarnings("unchecked")
        JdbcClient.MappedQuerySpec<AttachmentSearchRow> query =
                mock(JdbcClient.MappedQuerySpec.class);

        when(jdbc.sql(anyString())).thenReturn(statement);
        when(statement.param(anyString(), any())).thenReturn(statement);
        when(statement.query(AttachmentSearchRow.class)).thenReturn(query);
        when(query.list()).thenReturn(List.of());

        new AttachmentSearchRepository(jdbc).searchProposalAttachments(
                "2975", "", null, null, "all",
                "ORDER BY pv.proposal_number, pv.version_number, pa.proposal_attachment_id\n",
                25, 50
        );

        assertThat(firstSql(jdbc))
                .contains("ORDER BY pv.proposal_number, pv.version_number, pa.proposal_attachment_id")
                .contains("LIMIT :limit OFFSET :offset");
        verifyParam(statement, "limit", 25);
        verifyParam(statement, "offset", 50);
    }

    @Test
    void countSearchProposalAttachmentsAppliesTheSameFiltersAsSearch() {
        JdbcClient jdbc = mock(JdbcClient.class);
        JdbcClient.StatementSpec statement = mock(JdbcClient.StatementSpec.class);
        @SuppressWarnings("unchecked")
        JdbcClient.MappedQuerySpec<Long> query = mock(JdbcClient.MappedQuerySpec.class);

        when(jdbc.sql(anyString())).thenReturn(statement);
        when(statement.param(anyString(), any())).thenReturn(statement);
        when(statement.query(Long.class)).thenReturn(query);
        when(query.single()).thenReturn(1L);

        long total = new AttachmentSearchRepository(jdbc).countSearchProposalAttachments(
                "2975", "", null, null, "all"
        );

        assertThat(total).isEqualTo(1L);
        assertThat(firstSql(jdbc))
                .contains("SELECT COUNT(*)")
                .contains("FROM archive.proposal_attachment pa")
                .contains(":recordNumber = '' OR UPPER(pv.proposal_number) = UPPER(:recordNumber)");
    }

    // --- searchAllAttachments/countSearchAllAttachments ---

    @Test
    void searchAllAttachmentsIsASingleUnionAllQuery() {
        JdbcClient jdbc = mock(JdbcClient.class);
        JdbcClient.StatementSpec statement = mock(JdbcClient.StatementSpec.class);
        @SuppressWarnings("unchecked")
        JdbcClient.MappedQuerySpec<MixedAttachmentSearchRow> query =
                mock(JdbcClient.MappedQuerySpec.class);

        when(jdbc.sql(anyString())).thenReturn(statement);
        when(statement.param(anyString(), any())).thenReturn(statement);
        when(statement.query(MixedAttachmentSearchRow.class)).thenReturn(query);
        when(query.list()).thenReturn(List.of());

        new AttachmentSearchRepository(jdbc).searchAllAttachments(
                "879423", "", "all", "ORDER BY parent_number, sequence_number, record_type, attachment_id\n",
                25, 0
        );

        String sql = firstSql(jdbc);
        assertThat(sql)
                .contains("UNION ALL")
                .contains("'AWARD' AS record_type")
                .contains("'PROPOSAL' AS record_type")
                .contains("FROM archive.award_attachment aa")
                .contains("FROM archive.proposal_attachment pa");
        // Exactly one query issued for both domains together.
        assertThat(org.mockito.Mockito.mockingDetails(jdbc).getInvocations().stream()
                .filter(invocation -> invocation.getMethod().getName().equals("sql"))
                .count()).isEqualTo(1);
    }

    @Test
    void searchAllAttachmentsDoesNotAcceptRecordIdAttachmentIdOrFileIdParameters() {
        // Structural proof, not just a service-level rule: the method
        // signature itself has no such parameters to bind.
        var methods = AttachmentSearchRepository.class.getDeclaredMethods();
        var searchAll = java.util.Arrays.stream(methods)
                .filter(m -> m.getName().equals("searchAllAttachments"))
                .findFirst()
                .orElseThrow();
        var paramNames = java.util.Arrays.stream(searchAll.getParameters())
                .map(java.lang.reflect.Parameter::getName)
                .toList();
        assertThat(paramNames)
                .noneMatch(name -> name.toLowerCase().contains("recordid"))
                .noneMatch(name -> name.toLowerCase().contains("attachmentid"))
                .noneMatch(name -> name.toLowerCase().contains("fileid"));
    }

    @Test
    void searchAllAttachmentsOrderingIsDeterministicAcrossDomains() {
        JdbcClient jdbc = mock(JdbcClient.class);
        JdbcClient.StatementSpec statement = mock(JdbcClient.StatementSpec.class);
        @SuppressWarnings("unchecked")
        JdbcClient.MappedQuerySpec<MixedAttachmentSearchRow> query =
                mock(JdbcClient.MappedQuerySpec.class);

        when(jdbc.sql(anyString())).thenReturn(statement);
        when(statement.param(anyString(), any())).thenReturn(statement);
        when(statement.query(MixedAttachmentSearchRow.class)).thenReturn(query);
        when(query.list()).thenReturn(List.of());

        new AttachmentSearchRepository(jdbc).searchAllAttachments(
                "879423", "", "all",
                "ORDER BY parent_number, sequence_number, record_type, attachment_id\n",
                25, 0
        );

        assertThat(firstSql(jdbc))
                .contains("ORDER BY parent_number, sequence_number, record_type, attachment_id");
    }

    @Test
    void searchAllAttachmentsEveryRowCarriesRecordType() {
        JdbcClient jdbc = mock(JdbcClient.class);
        JdbcClient.StatementSpec statement = mock(JdbcClient.StatementSpec.class);
        @SuppressWarnings("unchecked")
        JdbcClient.MappedQuerySpec<MixedAttachmentSearchRow> query =
                mock(JdbcClient.MappedQuerySpec.class);

        when(jdbc.sql(anyString())).thenReturn(statement);
        when(statement.param(anyString(), any())).thenReturn(statement);
        when(statement.query(MixedAttachmentSearchRow.class)).thenReturn(query);
        when(query.list()).thenReturn(List.of());

        new AttachmentSearchRepository(jdbc).searchAllAttachments(
                "879423", "", "all", "ORDER BY parent_number\n", 25, 0
        );

        String selectClause = firstSql(jdbc).split("UNION ALL", 2)[0];
        String secondSelectClause = firstSql(jdbc).split("UNION ALL", 2)[1];
        assertThat(selectClause).contains("record_type");
        assertThat(secondSelectClause).contains("record_type");
    }

    @Test
    void countSearchAllAttachmentsIsAlsoASingleUnionAllQuery() {
        JdbcClient jdbc = mock(JdbcClient.class);
        JdbcClient.StatementSpec statement = mock(JdbcClient.StatementSpec.class);
        @SuppressWarnings("unchecked")
        JdbcClient.MappedQuerySpec<Long> query = mock(JdbcClient.MappedQuerySpec.class);

        when(jdbc.sql(anyString())).thenReturn(statement);
        when(statement.param(anyString(), any())).thenReturn(statement);
        when(statement.query(Long.class)).thenReturn(query);
        when(query.single()).thenReturn(3L);

        long total = new AttachmentSearchRepository(jdbc).countSearchAllAttachments(
                "879423", "", "all"
        );

        assertThat(total).isEqualTo(3L);
        assertThat(firstSql(jdbc))
                .contains("SELECT COUNT(*)")
                .contains("UNION ALL");
        assertThat(org.mockito.Mockito.mockingDetails(jdbc).getInvocations().stream()
                .filter(invocation -> invocation.getMethod().getName().equals("sql"))
                .count()).isEqualTo(1);
    }

    // --- searchNegotiationAttachments/countSearchNegotiationAttachments ---

    @Test
    void searchNegotiationAttachmentsMatchesOnExactDocumentNumber() {
        JdbcClient jdbc = mock(JdbcClient.class);
        JdbcClient.StatementSpec statement = mock(JdbcClient.StatementSpec.class);
        @SuppressWarnings("unchecked")
        JdbcClient.MappedQuerySpec<AttachmentSearchRow> query =
                mock(JdbcClient.MappedQuerySpec.class);

        when(jdbc.sql(anyString())).thenReturn(statement);
        when(statement.param(anyString(), any())).thenReturn(statement);
        when(statement.query(AttachmentSearchRow.class)).thenReturn(query);
        when(query.list()).thenReturn(List.of());

        new AttachmentSearchRepository(jdbc).searchNegotiationAttachments(
                "231427", "", null, null, "all", "ORDER BY n.document_number\n", 25, 0
        );

        assertThat(firstSql(jdbc))
                .contains(":recordNumber = '' OR UPPER(n.document_number) = UPPER(:recordNumber)")
                .contains("FROM archive.archived_attachment aa")
                .contains("JOIN archive.negotiation n ON n.negotiation_id = aa.parent_record_id")
                .contains("aa.module_code = 'NEGOTIATION'");
        verifyParam(statement, "recordNumber", "231427");
    }

    @Test
    void searchNegotiationAttachmentsExactNegotiationIdFilterUsesTheNullSafeCast() {
        JdbcClient jdbc = mock(JdbcClient.class);
        JdbcClient.StatementSpec statement = mock(JdbcClient.StatementSpec.class);
        @SuppressWarnings("unchecked")
        JdbcClient.MappedQuerySpec<AttachmentSearchRow> query =
                mock(JdbcClient.MappedQuerySpec.class);

        when(jdbc.sql(anyString())).thenReturn(statement);
        when(statement.param(anyString(), any())).thenReturn(statement);
        when(statement.query(AttachmentSearchRow.class)).thenReturn(query);
        when(query.list()).thenReturn(List.of());

        new AttachmentSearchRepository(jdbc).searchNegotiationAttachments(
                "", "", 374L, null, "all", "ORDER BY n.document_number\n", 25, 0
        );

        assertThat(firstSql(jdbc))
                .contains("CAST(:recordId AS BIGINT) IS NULL OR n.negotiation_id = :recordId");
        verifyParam(statement, "recordId", 374L);
    }

    @Test
    void searchNegotiationAttachmentsHistoricalVersionFilterExcludesEverything() {
        JdbcClient jdbc = mock(JdbcClient.class);
        JdbcClient.StatementSpec statement = mock(JdbcClient.StatementSpec.class);
        @SuppressWarnings("unchecked")
        JdbcClient.MappedQuerySpec<AttachmentSearchRow> query =
                mock(JdbcClient.MappedQuerySpec.class);

        when(jdbc.sql(anyString())).thenReturn(statement);
        when(statement.param(anyString(), any())).thenReturn(statement);
        when(statement.query(AttachmentSearchRow.class)).thenReturn(query);
        when(query.list()).thenReturn(List.of());

        new AttachmentSearchRepository(jdbc).searchNegotiationAttachments(
                "", "", 374L, null, "historical", "ORDER BY n.document_number\n", 25, 0
        );

        assertThat(firstSql(jdbc))
                .contains(":versionFilter <> 'historical'");
        verifyParam(statement, "versionFilter", "historical");
    }

    @Test
    void searchNegotiationAttachmentsNeverSelectsS3OrOtherStorageColumns() {
        JdbcClient jdbc = mock(JdbcClient.class);
        JdbcClient.StatementSpec statement = mock(JdbcClient.StatementSpec.class);
        @SuppressWarnings("unchecked")
        JdbcClient.MappedQuerySpec<AttachmentSearchRow> query =
                mock(JdbcClient.MappedQuerySpec.class);

        when(jdbc.sql(anyString())).thenReturn(statement);
        when(statement.param(anyString(), any())).thenReturn(statement);
        when(statement.query(AttachmentSearchRow.class)).thenReturn(query);
        when(query.list()).thenReturn(List.of());

        new AttachmentSearchRepository(jdbc).searchNegotiationAttachments(
                "231427", "", null, null, "all", "ORDER BY n.document_number\n", 25, 0
        );

        assertThat(firstSql(jdbc))
                .doesNotContain("s3_bucket AS")
                .doesNotContain("AS s3_bucket")
                .doesNotContain("s3_key AS")
                .doesNotContain("AS s3_key")
                .doesNotContain("source_file_id")
                .doesNotContain("legacy_restricted_flag")
                .doesNotContain("error_message");
    }

    @Test
    void searchNegotiationAttachmentsAlwaysReturnsNullFileIdAndTrueCurrentVersion() {
        JdbcClient jdbc = mock(JdbcClient.class);
        JdbcClient.StatementSpec statement = mock(JdbcClient.StatementSpec.class);
        @SuppressWarnings("unchecked")
        JdbcClient.MappedQuerySpec<AttachmentSearchRow> query =
                mock(JdbcClient.MappedQuerySpec.class);

        when(jdbc.sql(anyString())).thenReturn(statement);
        when(statement.param(anyString(), any())).thenReturn(statement);
        when(statement.query(AttachmentSearchRow.class)).thenReturn(query);
        when(query.list()).thenReturn(List.of());

        new AttachmentSearchRepository(jdbc).searchNegotiationAttachments(
                "231427", "", null, null, "all", "ORDER BY n.document_number\n", 25, 0
        );

        assertThat(firstSql(jdbc))
                .contains("CAST(NULL AS BIGINT) AS file_id")
                .contains("TRUE AS current_version");
    }

    @Test
    void countSearchAllAttachmentsUnionsAllThreeDomainsInOneQuery() {
        JdbcClient jdbc = mock(JdbcClient.class);
        JdbcClient.StatementSpec statement = mock(JdbcClient.StatementSpec.class);
        @SuppressWarnings("unchecked")
        JdbcClient.MappedQuerySpec<Long> query = mock(JdbcClient.MappedQuerySpec.class);

        when(jdbc.sql(anyString())).thenReturn(statement);
        when(statement.param(anyString(), any())).thenReturn(statement);
        when(statement.query(Long.class)).thenReturn(query);
        when(query.single()).thenReturn(0L);

        new AttachmentSearchRepository(jdbc).countSearchAllAttachments("879423", "", "all");

        String sql = firstSql(jdbc);
        assertThat(sql)
                .contains("archive.award_attachment")
                .contains("archive.proposal_attachment")
                .contains("aa2.module_code = 'NEGOTIATION'");
        assertThat(sql.split("UNION ALL", -1).length - 1).isEqualTo(2);
    }

    private void verifyParam(JdbcClient.StatementSpec statement, String name, Object value) {
        org.mockito.Mockito.verify(statement).param(name, value);
    }

    private String firstSql(JdbcClient jdbc) {
        return org.mockito.Mockito
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
    }
}
