package edu.bu.archive.adapter.out.persistence;

import edu.bu.archive.adapter.in.web.dto.negotiation.NegotiationActivityResponse;
import edu.bu.archive.adapter.in.web.dto.negotiation.NegotiationAttachmentResponse;
import edu.bu.archive.adapter.in.web.dto.negotiation.NegotiationCustomDataResponse;
import edu.bu.archive.adapter.in.web.dto.negotiation.NegotiationNotificationResponse;
import edu.bu.archive.adapter.in.web.dto.negotiation.NegotiationRowResponse;
import edu.bu.archive.adapter.in.web.dto.negotiation.NegotiationSummaryResponse;
import edu.bu.archive.adapter.in.web.dto.negotiation.NegotiationUnassociatedDetailResponse;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public class NegotiationArchiveRepository {

    private final JdbcClient jdbc;

    public NegotiationArchiveRepository(
            JdbcClient jdbc
    ) {
        this.jdbc = jdbc;
    }

    public long countNegotiations(String query) {
        String normalizedQuery = normalizeQuery(query);
        String filter = normalizedQuery.isEmpty()
                ? ""
                : """
                WHERE CAST(negotiation_id AS TEXT)
                        ILIKE '%' || :query || '%'
                   OR document_number ILIKE '%' || :query || '%'
                   OR negotiation_status_description
                        ILIKE '%' || :query || '%'
                   OR negotiation_agreement_type_description
                        ILIKE '%' || :query || '%'
                   OR negotiation_association_type_description
                        ILIKE '%' || :query || '%'
                   OR associated_document_id ILIKE '%' || :query || '%'
                   OR negotiator_full_name ILIKE '%' || :query || '%'
                """;

        JdbcClient.StatementSpec statement = jdbc.sql("""
                SELECT COUNT(*)
                FROM archive.negotiation
                """ + filter);
        if (!normalizedQuery.isEmpty()) {
            statement = statement.param("query", normalizedQuery);
        }
        Long count = statement
                .query(Long.class)
                .single();

        return count == null ? 0L : count;
    }

    public List<NegotiationSummaryResponse> findNegotiations(
            String query,
            int limit,
            int offset
    ) {
        String normalizedQuery = normalizeQuery(query);
        String filter = normalizedQuery.isEmpty()
                ? ""
                : """
                WHERE CAST(negotiation_id AS TEXT)
                        ILIKE '%' || :query || '%'
                   OR document_number ILIKE '%' || :query || '%'
                   OR negotiation_status_description
                        ILIKE '%' || :query || '%'
                   OR negotiation_agreement_type_description
                        ILIKE '%' || :query || '%'
                   OR negotiation_association_type_description
                        ILIKE '%' || :query || '%'
                   OR associated_document_id ILIKE '%' || :query || '%'
                   OR negotiator_full_name ILIKE '%' || :query || '%'
                """;

        JdbcClient.StatementSpec statement = jdbc.sql("""
                SELECT
                    negotiation_id,
                    document_number,
                    negotiation_status_id,
                    negotiation_status_code,
                    negotiation_status_description,
                    negotiation_agreement_type_id,
                    negotiation_agreement_type_code,
                    negotiation_agreement_type_description,
                    negotiation_association_type_id,
                    negotiation_association_type_code,
                    negotiation_association_type_description,
                    associated_document_id,
                    negotiator_person_id,
                    negotiator_full_name,
                    negotiation_start_date,
                    negotiation_end_date,
                    anticipated_award_date
                FROM archive.negotiation
                """ + filter + """
                ORDER BY
                """ + orderBy(normalizedQuery) + """
                    source_update_timestamp DESC NULLS LAST,
                    negotiation_id DESC
                LIMIT :limit
                OFFSET :offset
                """);
        if (!normalizedQuery.isEmpty()) {
            statement = statement.param("query", normalizedQuery);
        }
        return statement
                .param("limit", limit)
                .param("offset", offset)
                .query(NegotiationSummaryResponse.class)
                .list();
    }

    /*
     * An exact negotiation_id or document_number match is prioritized
     * ahead of the broad ILIKE substring matches the WHERE clause also
     * allows (e.g. searching "420" also matches negotiation_id 14200 or
     * a document_number containing "420") - otherwise a record a user
     * searched for by its exact ID can be pushed past the first page by
     * unrelated substring matches with a more recent
     * source_update_timestamp. Only applies when a query is present;
     * :query is unbound (and this CASE unreachable) on the empty-query
     * "browse all" path.
     */
    private String orderBy(String normalizedQuery) {
        return normalizedQuery.isEmpty()
                ? ""
                : """
                CASE WHEN CAST(negotiation_id AS TEXT) = :query
                        THEN 0 ELSE 1 END,
                CASE WHEN document_number = :query THEN 0 ELSE 1 END,
                """;
    }

    public Optional<NegotiationRowResponse> findById(
            long negotiationId
    ) {
        return jdbc.sql("""
                SELECT
                    negotiation_id,
                    document_number,
                    negotiation_status_id,
                    negotiation_status_code,
                    negotiation_status_description,
                    negotiation_agreement_type_id,
                    negotiation_agreement_type_code,
                    negotiation_agreement_type_description,
                    negotiation_association_type_id,
                    negotiation_association_type_code,
                    negotiation_association_type_description,
                    negotiator_person_id,
                    negotiator_full_name,
                    negotiation_start_date,
                    negotiation_end_date,
                    anticipated_award_date,
                    document_folder,
                    associated_document_id,
                    source_update_timestamp,
                    source_update_user,
                    source_version_number,
                    source_object_id,
                    document_source_update_timestamp,
                    document_source_update_user,
                    document_source_version_number,
                    document_source_object_id
                FROM archive.negotiation
                WHERE negotiation_id = :negotiationId
                """)
                .param("negotiationId", negotiationId)
                .query(NegotiationRowResponse.class)
                .optional();
    }

    public List<NegotiationActivityResponse> findActivities(
            long negotiationId
    ) {
        return jdbc.sql("""
                SELECT
                    negotiation_activity_id,
                    negotiation_id,
                    activity_type_id,
                    activity_type_code,
                    activity_type_description,
                    location_id,
                    location_code,
                    location_description,
                    start_date,
                    end_date,
                    create_date,
                    followup_date,
                    last_modified_user,
                    last_modified_date,
                    description,
                    restricted,
                    source_update_timestamp,
                    source_update_user,
                    source_version_number,
                    source_object_id
                FROM archive.negotiation_activity
                WHERE negotiation_id = :negotiationId
                ORDER BY
                    start_date DESC NULLS LAST,
                    negotiation_activity_id DESC
                """)
                .param("negotiationId", negotiationId)
                .query(NegotiationActivityResponse.class)
                .list();
    }

    public List<NegotiationCustomDataResponse> findCustomData(
            long negotiationId
    ) {
        return jdbc.sql("""
                SELECT
                    ncd.negotiation_custom_data_id,
                    ncd.negotiation_id,
                    ncd.negotiation_number,
                    ncd.custom_attribute_id,
                    ca.label AS label,
                    ca.name AS name,
                    ncd.value,
                    ncd.source_update_timestamp,
                    ncd.source_update_user,
                    ncd.source_version_number,
                    ncd.source_object_id
                FROM archive.negotiation_custom_data ncd
                LEFT JOIN archive.custom_attribute ca
                    ON ca.custom_attribute_id = ncd.custom_attribute_id
                WHERE ncd.negotiation_id = :negotiationId
                ORDER BY ncd.negotiation_custom_data_id
                """)
                .param("negotiationId", negotiationId)
                .query(NegotiationCustomDataResponse.class)
                .list();
    }

    public List<NegotiationNotificationResponse> findNotifications(
            long negotiationId
    ) {
        return jdbc.sql("""
                SELECT
                    notification_id,
                    notification_type_id,
                    document_number,
                    owning_document_id_fk,
                    recipients,
                    subject,
                    message,
                    source_update_timestamp,
                    source_update_user,
                    source_version_number,
                    source_object_id
                FROM archive.negotiation_notification
                WHERE owning_document_id_fk = :negotiationId
                ORDER BY
                    source_update_timestamp DESC NULLS LAST,
                    notification_id DESC
                """)
                .param("negotiationId", negotiationId)
                .query(NegotiationNotificationResponse.class)
                .list();
    }

    public List<NegotiationUnassociatedDetailResponse>
            findUnassociatedDetails(
                    long negotiationId
            ) {
        return jdbc.sql("""
                SELECT
                    negotiation_unassoc_detail_id,
                    negotiation_id,
                    title,
                    pi_person_id,
                    pi_rolodex_id,
                    lead_unit,
                    sponsor_code,
                    pi_name,
                    prime_sponsor_code,
                    sponsor_award_number,
                    contact_admin_person_id,
                    subaward_org,
                    source_update_timestamp,
                    source_update_user,
                    source_version_number,
                    source_object_id
                FROM archive.negotiation_unassociated_detail
                WHERE negotiation_id = :negotiationId
                ORDER BY negotiation_unassoc_detail_id
                """)
                .param("negotiationId", negotiationId)
                .query(NegotiationUnassociatedDetailResponse.class)
                .list();
    }

    /*
     * --- Attachments ---------------------------------------------------
     *
     * Negotiation has no domain-specific attachment table (unlike Award/
     * Subaward/Proposal) - it still uses the original generic V020
     * archive.archived_attachment destination, scoped by
     * module_code = 'NEGOTIATION'. activityId/sourceUpdateUser are
     * pulled from source_metadata JSONB, since the generic table's own
     * typed columns don't carry them (see NegotiationAttachmentPlugin).
     * downloadable mirrors the exact ARCHIVED + non-blank bucket/key
     * check downloadAttachment() enforces server-side.
     */
    public List<NegotiationAttachmentResponse> findAttachments(
            long negotiationId
    ) {
        return jdbc.sql("""
                SELECT
                    archived_attachment_id,
                    CAST(source_metadata->>'activity_id' AS BIGINT)
                        AS activity_id,
                    original_file_name,
                    content_type,
                    byte_size,
                    sha256,
                    archive_status,
                    source_update_timestamp,
                    source_metadata->>'source_update_user'
                        AS source_update_user,
                    (
                        archive_status = 'ARCHIVED'
                        AND s3_bucket IS NOT NULL AND s3_bucket <> ''
                        AND s3_key IS NOT NULL AND s3_key <> ''
                    ) AS downloadable,
                    legacy_restricted_flag AS restricted_flag
                FROM archive.archived_attachment
                WHERE module_code = 'NEGOTIATION'
                  AND parent_record_id = :negotiationId
                ORDER BY
                    CAST(source_metadata->>'activity_id' AS BIGINT),
                    archived_attachment_id
                """)
                .param("negotiationId", negotiationId)
                .query(NegotiationAttachmentResponse.class)
                .list();
    }

    public Optional<Long> findAttachmentNegotiationId(long attachmentId) {
        return jdbc.sql("""
                SELECT parent_record_id
                FROM archive.archived_attachment
                WHERE module_code = 'NEGOTIATION'
                  AND archived_attachment_id = :attachmentId
                """)
                .param("attachmentId", attachmentId)
                .query(Long.class)
                .optional();
    }

    public Optional<NegotiationArchivedAttachment> findArchivedAttachment(
            long negotiationId,
            long attachmentId
    ) {
        return jdbc.sql("""
                SELECT
                    archived_attachment_id,
                    parent_record_id,
                    original_file_name,
                    content_type,
                    s3_bucket,
                    s3_key,
                    byte_size,
                    archive_status
                FROM archive.archived_attachment
                WHERE module_code = 'NEGOTIATION'
                  AND archived_attachment_id = :attachmentId
                  AND parent_record_id = :negotiationId
                """)
                .param("attachmentId", attachmentId)
                .param("negotiationId", negotiationId)
                .query((rs, rowNum) -> new NegotiationArchivedAttachment(
                        rs.getLong("archived_attachment_id"),
                        rs.getLong("parent_record_id"),
                        rs.getString("original_file_name"),
                        rs.getString("content_type"),
                        rs.getString("s3_bucket"),
                        rs.getString("s3_key"),
                        (Long) rs.getObject("byte_size"),
                        rs.getString("archive_status")
                ))
                .optional();
    }

    /*
     * --- Association navigation -----------------------------------------
     *
     * Identifier semantics proven live against real Oracle data
     * 2026-08-06 (see NegotiationAssociatedRecordResponse) - never
     * guessed. AWD/IP resolve a business identifier to the current
     * archive version's internal id; SWD's associated_document_id is
     * already the internal subaward_id, so this only confirms it exists.
     */
    public Optional<Long> resolveCurrentAwardId(String awardNumber) {
        return jdbc.sql("""
                SELECT award_id
                FROM archive.award_version
                WHERE award_number = :awardNumber
                  AND is_primary_current = TRUE
                """)
                .param("awardNumber", awardNumber)
                .query(Long.class)
                .optional();
    }

    public Optional<Long> resolveCurrentProposalId(String proposalNumber) {
        return jdbc.sql("""
                SELECT proposal_id
                FROM archive.proposal_version
                WHERE proposal_number = :proposalNumber
                  AND proposal_sequence_status = 'ACTIVE'
                """)
                .param("proposalNumber", proposalNumber)
                .query(Long.class)
                .optional();
    }

    public boolean subawardExists(long subawardId) {
        Long id = jdbc.sql("""
                SELECT subaward_id
                FROM archive.subaward
                WHERE subaward_id = :subawardId
                """)
                .param("subawardId", subawardId)
                .query(Long.class)
                .optional()
                .orElse(null);
        return id != null;
    }

    private String normalizeQuery(String query) {
        return query == null ? "" : query.trim();
    }
}
