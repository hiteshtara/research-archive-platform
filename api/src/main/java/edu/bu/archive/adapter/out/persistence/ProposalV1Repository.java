package edu.bu.archive.adapter.out.persistence;

import edu.bu.archive.adapter.in.web.dto.proposal.ProposalAssociatedUnitResponse;
import edu.bu.archive.adapter.in.web.dto.proposal.ProposalAttachmentResponse;
import edu.bu.archive.adapter.in.web.dto.proposal.ProposalCommentRow;
import edu.bu.archive.adapter.in.web.dto.proposal.ProposalFundedAwardResponse;
import edu.bu.archive.adapter.in.web.dto.proposal.ProposalPersonResponse;
import edu.bu.archive.adapter.in.web.dto.proposal.ProposalSummaryResponse;
import edu.bu.archive.adapter.in.web.dto.proposal.ProposalUnitContactResponse;
import edu.bu.archive.adapter.in.web.dto.proposal.ProposalVersionSummaryResponse;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/*
 * Backs the versioned /api/v1/proposals API - keyed by the exact
 * surrogate proposalId (one specific version), mirroring
 * AwardV1Controller/AwardArchiveRepository's own convention. Distinct
 * from the older, family-number-scoped ProposalArchiveRepository
 * (/api/proposals/{proposalNumber}), which this class does not modify
 * or replace.
 */
@Repository
public class ProposalV1Repository {

    private final JdbcClient jdbc;

    public ProposalV1Repository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<ProposalSummaryResponse> findSummary(long proposalId) {
        return jdbc.sql("""
                SELECT
                    proposal_id,
                    proposal_number,
                    version_number AS sequence_number,
                    document_number AS workflow_document_number,
                    title,
                    status_description AS status,
                    proposal_sequence_status,
                    proposal_type,
                    activity_type,
                    lead_unit_number,
                    lead_unit_name,
                    sponsor_code,
                    sponsor_name,
                    principal_investigator_id,
                    principal_investigator_name,
                    initial_start_date,
                    initial_end_date,
                    initial_direct_cost,
                    initial_indirect_cost,
                    initial_total_cost,
                    total_start_date,
                    total_end_date,
                    total_direct_cost,
                    total_indirect_cost,
                    total_cost
                FROM archive.proposal_version
                WHERE proposal_id = :proposalId
                """)
                .param("proposalId", proposalId)
                .query(ProposalSummaryResponse.class)
                .optional();
    }

    public Optional<String> findProposalNumber(long proposalId) {
        return jdbc.sql("""
                SELECT proposal_number
                FROM archive.proposal_version
                WHERE proposal_id = :proposalId
                """)
                .param("proposalId", proposalId)
                .query(String.class)
                .optional();
    }

    public long countVersions(String proposalNumber) {
        Long count = jdbc.sql("""
                SELECT COUNT(*)
                FROM archive.proposal_version
                WHERE proposal_number = :proposalNumber
                """)
                .param("proposalNumber", proposalNumber)
                .query(Long.class)
                .single();

        return count == null ? 0L : count;
    }

    public List<ProposalVersionSummaryResponse> findVersionRows(
            String proposalNumber,
            int limit,
            int offset
    ) {
        return jdbc.sql("""
                SELECT
                    proposal_id,
                    proposal_number,
                    version_number AS sequence_number,
                    document_number AS workflow_document_number,
                    proposal_sequence_status,
                    status_description AS status,
                    title,
                    source_update_timestamp
                FROM archive.proposal_version
                WHERE proposal_number = :proposalNumber
                ORDER BY
                    version_number DESC,
                    source_update_timestamp DESC NULLS LAST,
                    proposal_id DESC
                LIMIT :limit OFFSET :offset
                """)
                .param("proposalNumber", proposalNumber)
                .param("limit", limit)
                .param("offset", offset)
                .query(ProposalVersionSummaryResponse.class)
                .list();
    }

    /*
     * PI/MPI/COI/KP via contactRoleCode - the same shared vocabulary
     * already used by archive.award_person (live-verified identical
     * values on both sides). Ordered PI first, then insertion order.
     */
    public List<ProposalPersonResponse> findPersonRows(long proposalId) {
        return jdbc.sql("""
                SELECT
                    proposal_person_id,
                    person_id,
                    full_name,
                    contact_role_code,
                    key_person_project_role,
                    (contact_role_code = 'PI') AS principal_investigator,
                    faculty_flag,
                    academic_year_effort,
                    calendar_year_effort,
                    summer_effort,
                    total_effort
                FROM archive.proposal_person
                WHERE proposal_id = :proposalId
                ORDER BY
                    CASE WHEN contact_role_code = 'PI' THEN 0 ELSE 1 END,
                    proposal_person_id
                """)
                .param("proposalId", proposalId)
                .query(ProposalPersonResponse.class)
                .list();
    }

    /*
     * archive.proposal_person_unit joined back to archive.proposal_person
     * (personName) and the shared archive.unit reference table
     * (unitName) - never archive.proposal_unit_contact, a genuinely
     * separate sibling table returned by findUnitContactRows below.
     */
    public List<ProposalAssociatedUnitResponse> findAssociatedUnitRows(
            long proposalId
    ) {
        return jdbc.sql("""
                SELECT
                    ppu.proposal_person_unit_id,
                    ppu.proposal_person_id,
                    pp.full_name AS person_name,
                    ppu.unit_number,
                    u.unit_name,
                    (ppu.lead_unit_flag = 'Y') AS lead_unit
                FROM archive.proposal_person_unit ppu
                JOIN archive.proposal_person pp
                    ON pp.proposal_person_id = ppu.proposal_person_id
                LEFT JOIN archive.unit u
                    ON u.unit_number = ppu.unit_number
                WHERE ppu.proposal_id = :proposalId
                ORDER BY ppu.proposal_person_unit_id
                """)
                .param("proposalId", proposalId)
                .query(ProposalAssociatedUnitResponse.class)
                .list();
    }

    public List<ProposalUnitContactResponse> findUnitContactRows(
            long proposalId
    ) {
        return jdbc.sql("""
                SELECT
                    puc.proposal_unit_contact_id,
                    puc.person_id,
                    puc.full_name,
                    puc.unit_administrator_type_code,
                    uat.description AS unit_administrator_type_description,
                    puc.unit_contact_type
                FROM archive.proposal_unit_contact puc
                LEFT JOIN archive.unit_administrator_type uat
                    ON uat.unit_administrator_type_code
                        = puc.unit_administrator_type_code
                WHERE puc.proposal_id = :proposalId
                ORDER BY puc.proposal_unit_contact_id
                """)
                .param("proposalId", proposalId)
                .query(ProposalUnitContactResponse.class)
                .list();
    }

    /*
     * downloadable mirrors the exact UPLOADED + non-blank bucket/key
     * check ProposalArchiveV1Service.downloadAttachment enforces
     * server-side - see AwardArchiveRepository.findAttachments for the
     * identical convention.
     */
    public List<ProposalAttachmentResponse> findAttachmentRows(
            long proposalId
    ) {
        return jdbc.sql("""
                SELECT
                    proposal_attachment_id,
                    sequence_number,
                    attachment_number,
                    attachment_title,
                    attachment_type_code,
                    attachment_type_description,
                    file_name,
                    content_type,
                    comments,
                    file_size AS file_size_bytes,
                    upload_status,
                    (
                        upload_status = 'UPLOADED'
                        AND s3_bucket IS NOT NULL AND s3_bucket <> ''
                        AND object_key IS NOT NULL AND object_key <> ''
                    ) AS downloadable,
                    source_update_timestamp
                FROM archive.proposal_attachment
                WHERE proposal_id = :proposalId
                ORDER BY
                    attachment_type_code NULLS LAST,
                    attachment_number NULLS LAST,
                    proposal_attachment_id
                """)
                .param("proposalId", proposalId)
                .query(ProposalAttachmentResponse.class)
                .list();
    }

    public Optional<Long> findAttachmentProposalId(long attachmentId) {
        return jdbc.sql("""
                SELECT proposal_id
                FROM archive.proposal_attachment
                WHERE proposal_attachment_id = :attachmentId
                """)
                .param("attachmentId", attachmentId)
                .query(Long.class)
                .optional();
    }

    public Optional<ProposalArchivedAttachment> findArchivedAttachment(
            long proposalId,
            long attachmentId
    ) {
        return jdbc.sql("""
                SELECT
                    proposal_attachment_id,
                    proposal_id,
                    file_name,
                    content_type,
                    s3_bucket,
                    object_key AS s3_key,
                    file_size AS file_size_bytes,
                    upload_status
                FROM archive.proposal_attachment
                WHERE proposal_id = :proposalId
                  AND proposal_attachment_id = :attachmentId
                """)
                .param("proposalId", proposalId)
                .param("attachmentId", attachmentId)
                .query(ProposalArchivedAttachment.class)
                .optional();
    }

    /*
     * --- Comments ----------------------------------------------------
     *
     * Starts FROM archive.comment_type (not proposal_comment), LEFT
     * JOINed to proposal_comment scoped by proposal_number (the whole
     * version family, mirroring Award's own family-wide comment
     * scoping) - so every displayed category is represented even when
     * this Proposal family has zero comments of that type.
     * ProposalArchiveV1Service turns that all-null row into a "no
     * comment recorded" category. Filtered to codes 12/13 explicitly -
     * archive.comment_type has no institutional-proposal-equivalent of
     * award_comment_screen_flag to data-drive this (see V063's
     * migration comment).
     */
    public List<ProposalCommentRow> findCommentRows(String proposalNumber) {
        return jdbc.sql("""
                SELECT
                    ct.comment_type_code,
                    ct.description AS comment_type_description,
                    pc.proposal_comment_id,
                    pc.proposal_id,
                    pc.sequence_number,
                    pc.comments,
                    pc.source_update_timestamp,
                    pc.source_update_user
                FROM archive.comment_type ct
                LEFT JOIN archive.proposal_comment pc
                    ON pc.comment_type_code = ct.comment_type_code
                    AND pc.proposal_number = :proposalNumber
                WHERE ct.comment_type_code IN ('12', '13')
                ORDER BY
                    ct.comment_type_code,
                    pc.sequence_number DESC,
                    pc.source_update_timestamp DESC NULLS LAST,
                    pc.proposal_comment_id DESC
                """)
                .param("proposalNumber", proposalNumber)
                .query(ProposalCommentRow.class)
                .list();
    }

    /*
     * --- Funded Awards ------------------------------------------------
     *
     * Family-wide (every proposal_id in this Proposal's whole
     * proposal_number family), matching Kuali's own
     * AllFundingProposalQueryCustomizer business logic - see
     * docs/kuali-business-rules/InstitutionalProposal.md's Award
     * relationship section. active = TRUE excludes deactivated links;
     * is_primary_current resolves each linked Award to its own current
     * version for status. Never selects award_id - internal Award IDs
     * are resolved separately, only at click-time, via
     * AwardV1Controller.resolveByNumber.
     */
    public List<ProposalFundedAwardResponse> findFundedAwardRows(
            String proposalNumber
    ) {
        return jdbc.sql("""
                SELECT DISTINCT
                    av.award_number,
                    av.sequence_number,
                    av.status_description AS status
                FROM archive.proposal_version pv
                JOIN archive.proposal_award pa
                    ON pa.proposal_id = pv.proposal_id
                JOIN archive.award_version av
                    ON av.award_id = pa.award_id
                WHERE pv.proposal_number = :proposalNumber
                  AND pa.active = TRUE
                  AND av.is_primary_current = TRUE
                ORDER BY av.award_number
                """)
                .param("proposalNumber", proposalNumber)
                .query(ProposalFundedAwardResponse.class)
                .list();
    }
}
