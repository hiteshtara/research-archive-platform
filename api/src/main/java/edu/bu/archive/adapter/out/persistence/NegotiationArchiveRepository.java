package edu.bu.archive.adapter.out.persistence;

import edu.bu.archive.adapter.in.web.dto.negotiation.NegotiationActivityResponse;
import edu.bu.archive.adapter.in.web.dto.negotiation.NegotiationAttachmentResponse;
import edu.bu.archive.adapter.in.web.dto.negotiation.NegotiationCustomDataResponse;
import edu.bu.archive.adapter.in.web.dto.negotiation.NegotiationNotificationResponse;
import edu.bu.archive.adapter.in.web.dto.negotiation.NegotiationRowResponse;
import edu.bu.archive.adapter.in.web.dto.negotiation.NegotiationSummaryResponse;
import edu.bu.archive.adapter.in.web.dto.negotiation.NegotiationUnassociatedDetailResponse;
import edu.bu.archive.application.negotiation.NegotiationSearchFilters;

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

    /*
     * The single FROM clause shared by the count and the page query, so
     * the total and the rows can never disagree about what matched.
     *
     * archive.negotiation_search_attribute is a 1:1 join on the primary
     * key, carrying the already-resolved Title/PI/Sponsor/Lead Unit. It
     * replaces the correlated LATERALs against award_version and
     * award_person that resolving those values at query time would
     * otherwise require - measured on dev RDS, those LATERALs cost
     * 2,489.8 ms on the COUNT(*) that drives pagination, because they
     * were evaluated for all 10,775 rows. See V080's migration header.
     */
    private static final String SEARCH_FROM = """
            FROM archive.negotiation n
            LEFT JOIN archive.negotiation_search_attribute a
                   ON a.negotiation_id = n.negotiation_id
            """;

    /*
     * Every filter is expressed as "(:param IS NULL OR <predicate>)" so a
     * single statement serves every combination of supplied filters and
     * an omitted filter adds no condition at all. All values are bound
     * parameters - no user input is ever concatenated into the SQL.
     *
     * The explicit CASTs are required, not cosmetic: PostgreSQL cannot
     * infer a type for a null bind parameter appearing only in an IS NULL
     * test, and fails with "could not determine data type of parameter".
     *
     * Free text is ANDed with the structured filters, not ORed, and it
     * searches the resolved attributes too - so a free-text "Addgene"
     * finds Negotiations whose sponsor resolved from an associated Award
     * as well as those whose sponsor came from an unassociated-detail
     * row.
     */
    private static final String SEARCH_WHERE = """
            WHERE (CAST(:query AS TEXT) IS NULL OR (
                       CAST(n.negotiation_id AS TEXT)
                           ILIKE '%' || :query || '%'
                    OR n.document_number ILIKE '%' || :query || '%'
                    OR n.negotiation_status_description
                           ILIKE '%' || :query || '%'
                    OR n.negotiation_agreement_type_description
                           ILIKE '%' || :query || '%'
                    OR n.negotiation_association_type_description
                           ILIKE '%' || :query || '%'
                    OR n.associated_document_id ILIKE '%' || :query || '%'
                    OR n.negotiator_full_name ILIKE '%' || :query || '%'
                    OR a.title ILIKE '%' || :query || '%'
                    OR a.principal_investigator_name
                           ILIKE '%' || :query || '%'
                    OR a.sponsor_name ILIKE '%' || :query || '%'
                    OR a.sponsor_code ILIKE '%' || :query || '%'
                    OR a.lead_unit_name ILIKE '%' || :query || '%'
                    OR a.lead_unit_number ILIKE '%' || :query || '%'
              ))
              AND (CAST(:status AS TEXT) IS NULL
                   OR n.negotiation_status_description = :status)
              AND (CAST(:agreementType AS TEXT) IS NULL
                   OR n.negotiation_agreement_type_description
                      = :agreementType)
              AND (CAST(:associationType AS TEXT) IS NULL
                   OR n.negotiation_association_type_description
                      = :associationType)
              AND (CAST(:associationId AS TEXT) IS NULL
                   OR n.associated_document_id = :associationId)
              AND (CAST(:negotiator AS TEXT) IS NULL
                   OR n.negotiator_full_name
                      ILIKE '%' || :negotiator || '%')
              AND (CAST(:principalInvestigator AS TEXT) IS NULL
                   OR a.principal_investigator_name
                      ILIKE '%' || :principalInvestigator || '%')
              AND (CAST(:sponsor AS TEXT) IS NULL
                   OR a.sponsor_name ILIKE '%' || :sponsor || '%'
                   OR a.sponsor_code ILIKE '%' || :sponsor || '%')
              AND (CAST(:leadUnit AS TEXT) IS NULL
                   OR a.lead_unit_name ILIKE '%' || :leadUnit || '%'
                   OR a.lead_unit_number ILIKE '%' || :leadUnit || '%')
              AND (CAST(:startDateFrom AS DATE) IS NULL
                   OR n.negotiation_start_date >= CAST(:startDateFrom AS DATE))
              AND (CAST(:startDateTo AS DATE) IS NULL
                   OR n.negotiation_start_date <= CAST(:startDateTo AS DATE))
              AND (CAST(:endDateFrom AS DATE) IS NULL
                   OR n.negotiation_end_date >= CAST(:endDateFrom AS DATE))
              AND (CAST(:endDateTo AS DATE) IS NULL
                   OR n.negotiation_end_date <= CAST(:endDateTo AS DATE))
            """;

    public long countNegotiations(NegotiationSearchFilters filters) {
        Long count = bind(jdbc.sql(
                "SELECT COUNT(*) " + SEARCH_FROM + SEARCH_WHERE), filters)
                .query(Long.class)
                .single();

        return count == null ? 0L : count;
    }

    public List<NegotiationSummaryResponse> findNegotiations(
            NegotiationSearchFilters filters,
            int limit,
            int offset
    ) {
        return bind(jdbc.sql("""
                SELECT
                    n.negotiation_id,
                    n.document_number,
                    n.negotiation_status_id,
                    n.negotiation_status_code,
                    n.negotiation_status_description,
                    n.negotiation_agreement_type_id,
                    n.negotiation_agreement_type_code,
                    n.negotiation_agreement_type_description,
                    n.negotiation_association_type_id,
                    n.negotiation_association_type_code,
                    n.negotiation_association_type_description,
                    n.associated_document_id,
                    n.negotiator_person_id,
                    n.negotiator_full_name,
                    n.negotiation_start_date,
                    n.negotiation_end_date,
                    n.anticipated_award_date,
                    a.title,
                    a.principal_investigator_name,
                    a.sponsor_code,
                    a.sponsor_name,
                    a.lead_unit_number,
                    a.lead_unit_name,
                    a.attribute_source
                """ + SEARCH_FROM + SEARCH_WHERE + """
                ORDER BY
                    CASE WHEN CAST(n.negotiation_id AS TEXT) = :query
                            THEN 0 ELSE 1 END,
                    CASE WHEN n.document_number = :query THEN 0 ELSE 1 END,
                    n.source_update_timestamp DESC NULLS LAST,
                    n.negotiation_id DESC
                LIMIT :limit
                OFFSET :offset
                """), filters)
                .param("limit", limit)
                .param("offset", offset)
                .query(NegotiationSummaryResponse.class)
                .list();
    }

    /*
     * Binds every filter parameter on every call, including the null
     * ones. JdbcClient rejects a statement whose named parameter was
     * never supplied, and the "(:param IS NULL OR ...)" form needs the
     * parameter present precisely so that it can be null.
     *
     * The ORDER BY's exact-match CASEs also reference :query, and are
     * reached on the browse-all path where it is null - a null :query
     * makes both CASE tests NULL, which falls to ELSE 1, leaving the
     * ordering to source_update_timestamp as intended. Prioritizing an
     * exact negotiation_id or document_number hit keeps a record someone
     * searched for by its exact ID off page four, where unrelated
     * substring matches with a newer timestamp would otherwise push it.
     */
    private JdbcClient.StatementSpec bind(
            JdbcClient.StatementSpec statement,
            NegotiationSearchFilters filters
    ) {
        return statement
                .param("query", filters.query())
                .param("status", filters.status())
                .param("agreementType", filters.agreementType())
                .param("associationType", filters.associationType())
                .param("associationId", filters.associationId())
                .param("negotiator", filters.negotiator())
                .param("principalInvestigator", filters.principalInvestigator())
                .param("sponsor", filters.sponsor())
                .param("leadUnit", filters.leadUnit())
                .param("startDateFrom", filters.startDateFrom())
                .param("startDateTo", filters.startDateTo())
                .param("endDateFrom", filters.endDateFrom())
                .param("endDateTo", filters.endDateTo());
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
                    archived_attachment_id AS attachment_id,
                    CAST(source_metadata->>'activity_id' AS BIGINT)
                        AS activity_id,
                    original_file_name AS file_name,
                    content_type,
                    byte_size AS file_size,
                    archive_status,
                    source_update_timestamp,
                    source_metadata->>'source_update_user'
                        AS source_update_user,
                    (
                        archive_status = 'ARCHIVED'
                        AND s3_bucket IS NOT NULL AND s3_bucket <> ''
                        AND s3_key IS NOT NULL AND s3_key <> ''
                    ) AS downloadable,
                    legacy_restricted_flag AS restricted_flag,
                    source_attachment_id AS oracle_attachment_id,
                    source_file_id AS oracle_file_id,
                    description
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
}
