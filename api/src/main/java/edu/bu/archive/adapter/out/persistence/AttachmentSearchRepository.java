package edu.bu.archive.adapter.out.persistence;

import edu.bu.archive.adapter.in.web.dto.attachment.AttachmentSearchRow;
import edu.bu.archive.adapter.in.web.dto.attachment.MixedAttachmentSearchRow;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.List;

/*
 * Archived File Finder Phase 2 (Proposal support + recordType=ALL).
 * Award-only search (recordType=AWARD) is untouched and still lives in
 * AwardArchiveRepository.searchAwardAttachments/countSearchAwardAttachments
 * - reused as-is by AttachmentSearchService, never duplicated here.
 * This class owns exactly the two new query shapes Phase 2 adds.
 */
@Repository
public class AttachmentSearchRepository {

    private final JdbcClient jdbc;

    public AttachmentSearchRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /*
     * archive.proposal_version.principal_investigator_name is already a
     * plain denormalized column (populated at Oracle extraction time via
     * a ROW_NUMBER()-ranked PROPOSAL_PERSONS join, contact_role_code =
     * 'PI', tie-broken by proposal_person_id DESC - see
     * sql/extract/proposal/01_proposal_versions.sql) - so, unlike
     * Award's award_version (which has no PI column and needs its own
     * LATERAL join to award_person), joining archive.proposal_attachment
     * to archive.proposal_version on the exact proposal_id (1:1 - each
     * proposal_id is itself one specific version, matching award_id's
     * own exact-version semantics) can never multiply rows through PI
     * resolution at all.
     *
     * "Current version" has no stored boolean on proposal_version
     * (unlike award_version.is_primary_current) - resolved via the same
     * ROW_NUMBER()-equivalent ranking (version_number DESC,
     * source_update_timestamp DESC NULLS LAST, proposal_id DESC)
     * already established and proven elsewhere in this codebase (see
     * ProposalArchiveRepository.findFamilies's own identical ranking),
     * via a LATERAL LIMIT 1 join - the same dedup-safe pattern Award's
     * PI resolution uses, applied here to version resolution instead.
     *
     * downloadable mirrors ProposalV1Repository.findAttachmentRows'
     * exact expression - proposal_attachment's real column is
     * object_key, not s3_key (a different name from Award's
     * attachment_object.s3_key) - and never selects s3_bucket/object_key/
     * file_data_id/checksum/error_message.
     *
     * FROM archive.proposal_attachment (never a table keyed only by the
     * shared file_data_id) guarantees one row per authoritative
     * proposal_attachment relationship, matching Award's own
     * FROM-award_attachment guarantee.
     */
    public List<AttachmentSearchRow> searchProposalAttachments(
            String recordNumber,
            String documentNumber,
            Long recordId,
            Long attachmentId,
            String versionFilter,
            String sortSql,
            int limit,
            int offset
    ) {
        return jdbc.sql("""
                SELECT
                    pv.proposal_id AS parent_id,
                    pv.proposal_number AS parent_number,
                    pv.title,
                    pv.principal_investigator_name AS principal_investigator,
                    pv.version_number AS sequence_number,
                    pv.document_number AS workflow_document_number,
                    pa.proposal_attachment_id AS attachment_id,
                    CAST(NULL AS BIGINT) AS file_id,
                    pa.file_name,
                    pa.attachment_type_description AS document_type,
                    pa.source_update_timestamp AS source_date,
                    pa.file_size AS file_size_bytes,
                    pa.content_type,
                    pa.upload_status,
                    (
                        pa.upload_status = 'UPLOADED'
                        AND pa.s3_bucket IS NOT NULL AND pa.s3_bucket <> ''
                        AND pa.object_key IS NOT NULL AND pa.object_key <> ''
                    ) AS downloadable,
                    (pv.proposal_id = current_pv.proposal_id) AS current_version
                FROM archive.proposal_attachment pa
                JOIN archive.proposal_version pv ON pv.proposal_id = pa.proposal_id
                LEFT JOIN LATERAL (
                    SELECT pv2.proposal_id
                    FROM archive.proposal_version pv2
                    WHERE pv2.proposal_number = pv.proposal_number
                    ORDER BY
                        pv2.version_number DESC,
                        pv2.source_update_timestamp DESC NULLS LAST,
                        pv2.proposal_id DESC
                    LIMIT 1
                ) current_pv ON TRUE
                WHERE (:recordNumber = '' OR UPPER(pv.proposal_number) = UPPER(:recordNumber))
                  AND (:documentNumber = '' OR UPPER(pv.document_number) = UPPER(:documentNumber))
                  AND (CAST(:recordId AS BIGINT) IS NULL OR pv.proposal_id = :recordId)
                  AND (CAST(:attachmentId AS BIGINT) IS NULL OR pa.proposal_attachment_id = :attachmentId)
                  AND (
                        :versionFilter = 'all'
                        OR (:versionFilter = 'current' AND pv.proposal_id = current_pv.proposal_id)
                        OR (:versionFilter = 'historical' AND pv.proposal_id != current_pv.proposal_id)
                  )
                """ + sortSql + """
                LIMIT :limit OFFSET :offset
                """)
                .param("recordNumber", recordNumber)
                .param("documentNumber", documentNumber)
                .param("recordId", recordId)
                .param("attachmentId", attachmentId)
                .param("versionFilter", versionFilter)
                .param("limit", limit)
                .param("offset", offset)
                .query(AttachmentSearchRow.class)
                .list();
    }

    public long countSearchProposalAttachments(
            String recordNumber,
            String documentNumber,
            Long recordId,
            Long attachmentId,
            String versionFilter
    ) {
        Long count = jdbc.sql("""
                SELECT COUNT(*)
                FROM archive.proposal_attachment pa
                JOIN archive.proposal_version pv ON pv.proposal_id = pa.proposal_id
                LEFT JOIN LATERAL (
                    SELECT pv2.proposal_id
                    FROM archive.proposal_version pv2
                    WHERE pv2.proposal_number = pv.proposal_number
                    ORDER BY
                        pv2.version_number DESC,
                        pv2.source_update_timestamp DESC NULLS LAST,
                        pv2.proposal_id DESC
                    LIMIT 1
                ) current_pv ON TRUE
                WHERE (:recordNumber = '' OR UPPER(pv.proposal_number) = UPPER(:recordNumber))
                  AND (:documentNumber = '' OR UPPER(pv.document_number) = UPPER(:documentNumber))
                  AND (CAST(:recordId AS BIGINT) IS NULL OR pv.proposal_id = :recordId)
                  AND (CAST(:attachmentId AS BIGINT) IS NULL OR pa.proposal_attachment_id = :attachmentId)
                  AND (
                        :versionFilter = 'all'
                        OR (:versionFilter = 'current' AND pv.proposal_id = current_pv.proposal_id)
                        OR (:versionFilter = 'historical' AND pv.proposal_id != current_pv.proposal_id)
                  )
                """)
                .param("recordNumber", recordNumber)
                .param("documentNumber", documentNumber)
                .param("recordId", recordId)
                .param("attachmentId", attachmentId)
                .param("versionFilter", versionFilter)
                .query(Long.class)
                .single();

        return count == null ? 0L : count;
    }

    /*
     * Negotiation has no domain-specific attachment table (see
     * NegotiationArchiveRepository.findAttachments's own comment) - this
     * joins the shared archive.archived_attachment (module_code =
     * 'NEGOTIATION') straight to archive.negotiation on
     * parent_record_id = negotiation_id, the exact same relationship
     * NegotiationArchiveRepository.findAttachments/findArchivedAttachment
     * already use, so downloadable here can never disagree with what the
     * real download endpoint enforces.
     *
     * Negotiation has no separate "record number" distinct from its own
     * workflow document_number (unlike Award/Proposal, which have a
     * business number distinct from their KEW document number) - see
     * docs/architecture/NEGOTIATION_ARCHIVE_COVERAGE.md's "business
     * grain" note. recordNumber and documentNumber therefore both filter
     * the same document_number column here; supplying either alone is
     * sufficient, and supplying both simply ANDs the same comparison
     * twice (harmless).
     *
     * title/document_type have no Negotiation-level or
     * archived_attachment-level equivalent - left NULL rather than
     * fabricated. principal_investigator is populated from
     * negotiator_full_name (the Negotiation's negotiator, not a PI -
     * Negotiation has no PI concept of its own) since this shared result
     * shape needs some value in that column; never a fabricated person.
     * fileId is intentionally never populated (CAST(NULL...)) -
     * Negotiation's own file identifier (source_file_id) is a VARCHAR
     * Oracle blob key, not a comparable numeric id, structurally the
     * same reasoning Proposal's fileId rejection already established -
     * AttachmentSearchService rejects the fileId parameter for
     * recordType=NEGOTIATION before this method is ever called.
     *
     * Negotiation has no version chain (business grain and historical
     * grain are the same thing - one row per real Negotiation) -
     * current_version is unconditionally TRUE, and "historical" matches
     * zero rows rather than fabricating a non-existent prior version.
     */
    public List<AttachmentSearchRow> searchNegotiationAttachments(
            String recordNumber,
            String documentNumber,
            Long recordId,
            Long attachmentId,
            String versionFilter,
            String sortSql,
            int limit,
            int offset
    ) {
        return jdbc.sql("""
                SELECT
                    n.negotiation_id AS parent_id,
                    n.document_number AS parent_number,
                    CAST(NULL AS TEXT) AS title,
                    n.negotiator_full_name AS principal_investigator,
                    CAST(NULL AS INTEGER) AS sequence_number,
                    n.document_number AS workflow_document_number,
                    aa.archived_attachment_id AS attachment_id,
                    CAST(NULL AS BIGINT) AS file_id,
                    aa.original_file_name AS file_name,
                    CAST(NULL AS TEXT) AS document_type,
                    aa.source_update_timestamp AS source_date,
                    aa.byte_size AS file_size_bytes,
                    aa.content_type,
                    aa.archive_status AS upload_status,
                    (
                        aa.archive_status = 'ARCHIVED'
                        AND aa.s3_bucket IS NOT NULL AND aa.s3_bucket <> ''
                        AND aa.s3_key IS NOT NULL AND aa.s3_key <> ''
                    ) AS downloadable,
                    TRUE AS current_version
                FROM archive.archived_attachment aa
                JOIN archive.negotiation n ON n.negotiation_id = aa.parent_record_id
                WHERE aa.module_code = 'NEGOTIATION'
                  AND (:recordNumber = '' OR UPPER(n.document_number) = UPPER(:recordNumber))
                  AND (:documentNumber = '' OR UPPER(n.document_number) = UPPER(:documentNumber))
                  AND (CAST(:recordId AS BIGINT) IS NULL OR n.negotiation_id = :recordId)
                  AND (CAST(:attachmentId AS BIGINT) IS NULL OR aa.archived_attachment_id = :attachmentId)
                  AND (:versionFilter <> 'historical')
                """ + sortSql + """
                LIMIT :limit OFFSET :offset
                """)
                .param("recordNumber", recordNumber)
                .param("documentNumber", documentNumber)
                .param("recordId", recordId)
                .param("attachmentId", attachmentId)
                .param("versionFilter", versionFilter)
                .param("limit", limit)
                .param("offset", offset)
                .query(AttachmentSearchRow.class)
                .list();
    }

    public long countSearchNegotiationAttachments(
            String recordNumber,
            String documentNumber,
            Long recordId,
            Long attachmentId,
            String versionFilter
    ) {
        Long count = jdbc.sql("""
                SELECT COUNT(*)
                FROM archive.archived_attachment aa
                JOIN archive.negotiation n ON n.negotiation_id = aa.parent_record_id
                WHERE aa.module_code = 'NEGOTIATION'
                  AND (:recordNumber = '' OR UPPER(n.document_number) = UPPER(:recordNumber))
                  AND (:documentNumber = '' OR UPPER(n.document_number) = UPPER(:documentNumber))
                  AND (CAST(:recordId AS BIGINT) IS NULL OR n.negotiation_id = :recordId)
                  AND (CAST(:attachmentId AS BIGINT) IS NULL OR aa.archived_attachment_id = :attachmentId)
                  AND (:versionFilter <> 'historical')
                """)
                .param("recordNumber", recordNumber)
                .param("documentNumber", documentNumber)
                .param("recordId", recordId)
                .param("attachmentId", attachmentId)
                .param("versionFilter", versionFilter)
                .query(Long.class)
                .single();

        return count == null ? 0L : count;
    }

    /*
     * recordType=ALL: a single database-level UNION ALL across Award,
     * Proposal, and Negotiation, one deterministic ORDER BY applied to
     * the combined set (never independently paginated lists merged
     * client-side). Deliberately restricted to recordNumber/
     * documentNumber/versionFilter only - recordId/attachmentId/fileId
     * are rejected by AttachmentSearchService before this method is
     * ever called, since each domain's recordId is an independent
     * numeric space that could otherwise collide ambiguously.
     * record_type is a literal per branch, not derived - the one field
     * a single-domain query doesn't need but a mixed one must carry on
     * every row.
     */
    public List<MixedAttachmentSearchRow> searchAllAttachments(
            String recordNumber,
            String documentNumber,
            String versionFilter,
            String sortSql,
            int limit,
            int offset
    ) {
        return jdbc.sql("""
                SELECT * FROM (
                    SELECT
                        'AWARD' AS record_type,
                        av.award_id AS parent_id,
                        av.award_number AS parent_number,
                        av.title,
                        pi.full_name AS principal_investigator,
                        av.sequence_number,
                        av.workflow_document_number,
                        aa.award_attachment_id AS attachment_id,
                        aa.file_id,
                        ao.file_name,
                        aa.type_code AS document_type,
                        aa.oracle_update_timestamp AS source_date,
                        ao.file_size_bytes,
                        ao.content_type,
                        ao.upload_status,
                        (
                            ao.upload_status = 'UPLOADED'
                            AND ao.s3_bucket IS NOT NULL AND ao.s3_bucket <> ''
                            AND ao.s3_key IS NOT NULL AND ao.s3_key <> ''
                        ) AS downloadable,
                        av.is_primary_current AS current_version
                    FROM archive.award_attachment aa
                    JOIN archive.award_version av ON av.award_id = aa.award_id
                    LEFT JOIN archive.attachment_object ao
                        ON ao.file_id = aa.file_id
                    LEFT JOIN LATERAL (
                        SELECT ap.full_name
                        FROM archive.award_person ap
                        WHERE ap.award_id = av.award_id
                        ORDER BY
                            CASE
                                WHEN UPPER(TRIM(ap.contact_role_code)) = 'PI'
                                THEN 0
                                ELSE 1
                            END,
                            ap.full_name NULLS LAST,
                            ap.award_person_id
                        LIMIT 1
                    ) pi ON TRUE
                    WHERE (:recordNumber = '' OR UPPER(av.award_number) = UPPER(:recordNumber))
                      AND (:documentNumber = '' OR UPPER(av.workflow_document_number) = UPPER(:documentNumber))
                      AND (
                            :versionFilter = 'all'
                            OR (:versionFilter = 'current' AND av.is_primary_current = TRUE)
                            OR (:versionFilter = 'historical' AND av.is_primary_current = FALSE)
                      )

                    UNION ALL

                    SELECT
                        'PROPOSAL' AS record_type,
                        pv.proposal_id AS parent_id,
                        pv.proposal_number AS parent_number,
                        pv.title,
                        pv.principal_investigator_name AS principal_investigator,
                        pv.version_number AS sequence_number,
                        pv.document_number AS workflow_document_number,
                        pa.proposal_attachment_id AS attachment_id,
                        CAST(NULL AS BIGINT) AS file_id,
                        pa.file_name,
                        pa.attachment_type_description AS document_type,
                        pa.source_update_timestamp AS source_date,
                        pa.file_size AS file_size_bytes,
                        pa.content_type,
                        pa.upload_status,
                        (
                            pa.upload_status = 'UPLOADED'
                            AND pa.s3_bucket IS NOT NULL AND pa.s3_bucket <> ''
                            AND pa.object_key IS NOT NULL AND pa.object_key <> ''
                        ) AS downloadable,
                        (pv.proposal_id = current_pv.proposal_id) AS current_version
                    FROM archive.proposal_attachment pa
                    JOIN archive.proposal_version pv ON pv.proposal_id = pa.proposal_id
                    LEFT JOIN LATERAL (
                        SELECT pv2.proposal_id
                        FROM archive.proposal_version pv2
                        WHERE pv2.proposal_number = pv.proposal_number
                        ORDER BY
                            pv2.version_number DESC,
                            pv2.source_update_timestamp DESC NULLS LAST,
                            pv2.proposal_id DESC
                        LIMIT 1
                    ) current_pv ON TRUE
                    WHERE (:recordNumber = '' OR UPPER(pv.proposal_number) = UPPER(:recordNumber))
                      AND (:documentNumber = '' OR UPPER(pv.document_number) = UPPER(:documentNumber))
                      AND (
                            :versionFilter = 'all'
                            OR (:versionFilter = 'current' AND pv.proposal_id = current_pv.proposal_id)
                            OR (:versionFilter = 'historical' AND pv.proposal_id != current_pv.proposal_id)
                      )

                    UNION ALL

                    SELECT
                        'NEGOTIATION' AS record_type,
                        n.negotiation_id AS parent_id,
                        n.document_number AS parent_number,
                        CAST(NULL AS TEXT) AS title,
                        n.negotiator_full_name AS principal_investigator,
                        CAST(NULL AS INTEGER) AS sequence_number,
                        n.document_number AS workflow_document_number,
                        aa2.archived_attachment_id AS attachment_id,
                        CAST(NULL AS BIGINT) AS file_id,
                        aa2.original_file_name AS file_name,
                        CAST(NULL AS TEXT) AS document_type,
                        aa2.source_update_timestamp AS source_date,
                        aa2.byte_size AS file_size_bytes,
                        aa2.content_type,
                        aa2.archive_status AS upload_status,
                        (
                            aa2.archive_status = 'ARCHIVED'
                            AND aa2.s3_bucket IS NOT NULL AND aa2.s3_bucket <> ''
                            AND aa2.s3_key IS NOT NULL AND aa2.s3_key <> ''
                        ) AS downloadable,
                        TRUE AS current_version
                    FROM archive.archived_attachment aa2
                    JOIN archive.negotiation n ON n.negotiation_id = aa2.parent_record_id
                    WHERE aa2.module_code = 'NEGOTIATION'
                      AND (:recordNumber = '' OR UPPER(n.document_number) = UPPER(:recordNumber))
                      AND (:documentNumber = '' OR UPPER(n.document_number) = UPPER(:documentNumber))
                      AND (:versionFilter <> 'historical')
                ) combined
                """ + sortSql + """
                LIMIT :limit OFFSET :offset
                """)
                .param("recordNumber", recordNumber)
                .param("documentNumber", documentNumber)
                .param("versionFilter", versionFilter)
                .param("limit", limit)
                .param("offset", offset)
                .query(MixedAttachmentSearchRow.class)
                .list();
    }

    public long countSearchAllAttachments(
            String recordNumber,
            String documentNumber,
            String versionFilter
    ) {
        Long count = jdbc.sql("""
                SELECT COUNT(*) FROM (
                    SELECT av.award_id
                    FROM archive.award_attachment aa
                    JOIN archive.award_version av ON av.award_id = aa.award_id
                    WHERE (:recordNumber = '' OR UPPER(av.award_number) = UPPER(:recordNumber))
                      AND (:documentNumber = '' OR UPPER(av.workflow_document_number) = UPPER(:documentNumber))
                      AND (
                            :versionFilter = 'all'
                            OR (:versionFilter = 'current' AND av.is_primary_current = TRUE)
                            OR (:versionFilter = 'historical' AND av.is_primary_current = FALSE)
                      )

                    UNION ALL

                    SELECT pv.proposal_id
                    FROM archive.proposal_attachment pa
                    JOIN archive.proposal_version pv ON pv.proposal_id = pa.proposal_id
                    LEFT JOIN LATERAL (
                        SELECT pv2.proposal_id
                        FROM archive.proposal_version pv2
                        WHERE pv2.proposal_number = pv.proposal_number
                        ORDER BY
                            pv2.version_number DESC,
                            pv2.source_update_timestamp DESC NULLS LAST,
                            pv2.proposal_id DESC
                        LIMIT 1
                    ) current_pv ON TRUE
                    WHERE (:recordNumber = '' OR UPPER(pv.proposal_number) = UPPER(:recordNumber))
                      AND (:documentNumber = '' OR UPPER(pv.document_number) = UPPER(:documentNumber))
                      AND (
                            :versionFilter = 'all'
                            OR (:versionFilter = 'current' AND pv.proposal_id = current_pv.proposal_id)
                            OR (:versionFilter = 'historical' AND pv.proposal_id != current_pv.proposal_id)
                      )

                    UNION ALL

                    SELECT n.negotiation_id
                    FROM archive.archived_attachment aa2
                    JOIN archive.negotiation n ON n.negotiation_id = aa2.parent_record_id
                    WHERE aa2.module_code = 'NEGOTIATION'
                      AND (:recordNumber = '' OR UPPER(n.document_number) = UPPER(:recordNumber))
                      AND (:documentNumber = '' OR UPPER(n.document_number) = UPPER(:documentNumber))
                      AND (:versionFilter <> 'historical')
                ) combined
                """)
                .param("recordNumber", recordNumber)
                .param("documentNumber", documentNumber)
                .param("versionFilter", versionFilter)
                .query(Long.class)
                .single();

        return count == null ? 0L : count;
    }
}
