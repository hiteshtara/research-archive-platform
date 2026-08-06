package edu.bu.archive.adapter.out.persistence;

import edu.bu.archive.adapter.in.web.dto.subaward.SubawardAmountResponse;
import edu.bu.archive.adapter.in.web.dto.subaward.SubawardAttachmentResponse;
import edu.bu.archive.adapter.in.web.dto.subaward.SubawardCloseoutResponse;
import edu.bu.archive.adapter.in.web.dto.subaward.SubawardContactResponse;
import edu.bu.archive.adapter.in.web.dto.subaward.SubawardCustomDataResponse;
import edu.bu.archive.adapter.in.web.dto.subaward.SubawardFundingResponse;
import edu.bu.archive.adapter.in.web.dto.subaward.SubawardNotepadResponse;
import edu.bu.archive.adapter.in.web.dto.subaward.SubawardNotificationResponse;
import edu.bu.archive.adapter.in.web.dto.subaward.SubawardReportResponse;
import edu.bu.archive.adapter.in.web.dto.subaward.SubawardRowResponse;
import edu.bu.archive.adapter.in.web.dto.subaward.SubawardSummaryResponse;
import edu.bu.archive.adapter.in.web.dto.subaward.SubawardTemplateInfoResponse;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public class SubawardArchiveRepository {

    private final JdbcClient jdbc;

    public SubawardArchiveRepository(
            JdbcClient jdbc
    ) {
        this.jdbc = jdbc;
    }

    public long countSubawards(String query) {
        String normalizedQuery = normalizeQuery(query);
        String filter = subawardFilter(normalizedQuery);

        JdbcClient.StatementSpec statement = jdbc.sql("""
                SELECT COUNT(*)
                FROM archive.subaward
                """ + filter);
        if (!normalizedQuery.isEmpty()) {
            statement = statement.param("query", normalizedQuery);
        }
        Long count = statement
                .query(Long.class)
                .single();

        return count == null ? 0L : count;
    }

    public List<SubawardSummaryResponse> findSubawards(
            String query,
            int limit,
            int offset
    ) {
        String normalizedQuery = normalizeQuery(query);
        String filter = subawardFilter(normalizedQuery);
        String orderBy = normalizedQuery.isEmpty()
                ? """
                ORDER BY subaward_id DESC
                """
                : """
                ORDER BY
                    source_update_timestamp DESC NULLS LAST,
                    sequence_number DESC,
                    subaward_id DESC
                """;

        String sql = """
                SELECT
                    subaward_id,
                    subaward_code,
                    sequence_number,
                    document_number,
                    title,
                    status_code,
                    status_description,
                    organization_id,
                    account_number,
                    start_date,
                    end_date,
                    subaward_sequence_status,
                    source_update_timestamp
                FROM archive.subaward
                %s
                %s
                LIMIT :limit
                OFFSET :offset
                """.formatted(filter, orderBy);
        JdbcClient.StatementSpec statement = jdbc.sql(sql);
        if (!normalizedQuery.isEmpty()) {
            statement = statement.param("query", normalizedQuery);
        }
        return statement
                .param("limit", limit)
                .param("offset", offset)
                .query(SubawardSummaryResponse.class)
                .list();
    }

    private String subawardFilter(String normalizedQuery) {
        return normalizedQuery.isEmpty()
                ? ""
                : """
                WHERE CAST(subaward_id AS TEXT)
                        ILIKE '%' || :query || '%'
                   OR subaward_code ILIKE '%' || :query || '%'
                   OR document_number ILIKE '%' || :query || '%'
                   OR title ILIKE '%' || :query || '%'
                   OR status_description ILIKE '%' || :query || '%'
                   OR organization_id ILIKE '%' || :query || '%'
                   OR account_number ILIKE '%' || :query || '%'
                   OR award_prime_sponsor_name ILIKE '%' || :query || '%'
                   OR award_sponsor_name ILIKE '%' || :query || '%'
                """;
    }

    public Optional<SubawardRowResponse> findById(long subawardId) {
        return jdbc.sql("""
                SELECT
                    subaward_id,
                    document_number,
                    sequence_number,
                    subaward_code,
                    organization_id,
                    start_date,
                    end_date,
                    subaward_type_code,
                    purchase_order_num,
                    title,
                    status_code,
                    status_description,
                    account_number,
                    vendor_number,
                    requisitioner_id,
                    requisitioner_unit,
                    archive_location,
                    closeout_date,
                    comments,
                    site_investigator,
                    cost_type,
                    date_of_fully_executed,
                    requisition_number,
                    fed_award_proj_desc,
                    f_and_a_rate,
                    de_minimus,
                    subaward_sequence_status,
                    ffata_required,
                    fsrs_subaward_number,
                    award_prime_sponsor_name,
                    award_sponsor_name,
                    extension_date_received,
                    source_update_timestamp,
                    source_update_user,
                    source_version_number,
                    source_object_id,
                    document_source_update_timestamp,
                    document_source_update_user,
                    document_source_version_number,
                    document_source_object_id
                FROM archive.subaward
                WHERE subaward_id = :subawardId
                """)
                .param("subawardId", subawardId)
                .query(SubawardRowResponse.class)
                .optional();
    }

    public List<SubawardAmountResponse> findAmounts(long subawardId) {
        return jdbc.sql("""
                SELECT
                    subaward_amount_info_id,
                    subaward_id,
                    subaward_code,
                    sequence_number,
                    obligated_amount,
                    obligated_change,
                    obligated_change_direct,
                    obligated_change_indirect,
                    anticipated_amount,
                    anticipated_change,
                    anticipated_change_direct,
                    anticipated_change_indirect,
                    rate,
                    effective_date,
                    modification_effective_date,
                    modification_number,
                    modification_type_code,
                    modification_type_description,
                    performance_start_date,
                    performance_end_date,
                    purchase_order_num,
                    comments,
                    file_data_id,
                    file_name,
                    mime_type,
                    source_update_timestamp,
                    source_update_user,
                    source_version_number,
                    source_object_id
                FROM archive.subaward_amount
                WHERE subaward_id = :subawardId
                ORDER BY
                    effective_date DESC NULLS LAST,
                    subaward_amount_info_id DESC
                """)
                .param("subawardId", subawardId)
                .query(SubawardAmountResponse.class)
                .list();
    }

    /*
     * A contact row is linked to EITHER archive.rolodex OR
     * archive.person, never both (proven from
     * SubAwardContact.setRolodex()/setKcPerson(), which are mutually
     * exclusive) - COALESCE across both LEFT JOINs is therefore safe,
     * never double-matching. Mirrors
     * AwardArchiveRepository.findSponsorContacts's rolodex-resolution
     * pattern. archive.person is deliberately scoped only to
     * person_ids already referenced elsewhere (see
     * archive.person's own table comment) - a requisitioner_id outside
     * that scope resolves to null full_name/email/phone here, not a
     * guess. organization has no equivalent on archive.person, so it
     * only ever resolves through the rolodex path.
     */
    public List<SubawardContactResponse> findContacts(long subawardId) {
        return jdbc.sql("""
                SELECT
                    contact.subaward_contact_id,
                    contact.subaward_id,
                    contact.subaward_code,
                    contact.sequence_number,
                    contact.contact_type_code,
                    contact.contact_type_description,
                    COALESCE(
                        person.full_name,
                        CASE
                            WHEN rolodex.last_name IS NOT NULL
                                AND rolodex.first_name IS NOT NULL
                            THEN rolodex.last_name || ', ' || rolodex.first_name
                            ELSE COALESCE(rolodex.last_name, rolodex.first_name)
                        END
                    ) AS full_name,
                    rolodex.organization,
                    COALESCE(person.email_address, rolodex.email_address) AS email,
                    COALESCE(person.phone_number, rolodex.phone_number) AS phone,
                    contact.rolodex_id,
                    contact.requisitioner_id,
                    contact.source_update_timestamp,
                    contact.source_update_user,
                    contact.source_version_number,
                    contact.source_object_id
                FROM archive.subaward_contact contact
                LEFT JOIN archive.rolodex rolodex
                    ON rolodex.rolodex_id = contact.rolodex_id
                LEFT JOIN archive.person person
                    ON person.person_id = contact.requisitioner_id
                WHERE contact.subaward_id = :subawardId
                ORDER BY contact.contact_type_code NULLS LAST,
                    contact.subaward_contact_id
                """)
                .param("subawardId", subawardId)
                .query(SubawardContactResponse.class)
                .list();
    }

    public List<SubawardCustomDataResponse> findCustomData(long subawardId) {
        return jdbc.sql("""
                SELECT
                    subaward_custom_data_id,
                    subaward_id,
                    subaward_code,
                    sequence_number,
                    custom_attribute_id,
                    value,
                    source_update_timestamp,
                    source_update_user,
                    source_version_number,
                    source_object_id
                FROM archive.subaward_custom_data
                WHERE subaward_id = :subawardId
                ORDER BY custom_attribute_id NULLS LAST,
                    subaward_custom_data_id
                """)
                .param("subawardId", subawardId)
                .query(SubawardCustomDataResponse.class)
                .list();
    }

    /*
     * Award linkage resolution - proven live against real dev data
     * 2026-08-06 (fixtures 1363/1414, both currently unresolved; Award
     * family 202505-00002 loaded to prove the resolved case), same
     * "prove, don't guess" discipline as
     * NegotiationArchiveRepository.resolveCurrentAwardId. funding.award_id
     * is the exact, authoritative link
     * (SubAwardFundingSource.awardId -> Award, from
     * repository-subAward.xml) but is only ever a raw internal Award
     * version id - never assumed current. award_number is denormalized
     * onto subaward_funding at ETL time (see
     * oracle/subaward/export_subaward_funding.sql) precisely so this
     * query can still report "Associated Award <number>" even when
     * neither the exact linked version nor any other version of that
     * Award family has been archived. current_award resolves the
     * family's actual current version by award_number against
     * is_primary_current, independently of whichever version
     * award_id happened to point at.
     *
     * One row per real archive.subaward_funding row - a Subaward can
     * have MULTIPLE, each to a different Award family (proven via
     * Kuali's own duplicate-award-number business rule and confirmed
     * in real archived data), never collapsed to "the" one. amount
     * mirrors findSummaryCards's currentObligatedAmount LATERAL JOIN
     * pattern exactly (most recent archive.award_amount_info row by
     * source_version_number for the resolved current Award version).
     */
    public List<SubawardFundingResponse> findFunding(long subawardId) {
        return jdbc.sql("""
                SELECT
                    funding.subaward_funding_source_id,
                    funding.subaward_id,
                    funding.subaward_code,
                    funding.sequence_number,
                    funding.award_id AS exact_linked_award_id,
                    funding.award_number,
                    current_award.title AS award_title,
                    current_award.status_description AS award_status,
                    current_award.sponsor_name AS award_sponsor,
                    amt.obligated_total_amount AS award_amount,
                    current_award.award_id AS navigable_current_award_id,
                    current_award.award_id IS NOT NULL AS archived,
                    funding.source_update_timestamp,
                    funding.source_update_user,
                    funding.source_version_number,
                    funding.source_object_id
                FROM archive.subaward_funding funding
                LEFT JOIN archive.award_version current_award
                  ON current_award.award_number = funding.award_number
                 AND current_award.is_primary_current = TRUE
                LEFT JOIN LATERAL (
                    SELECT ai.obligated_total_amount
                    FROM archive.award_amount_info ai
                    WHERE ai.award_id = current_award.award_id
                    ORDER BY
                        ai.source_version_number DESC NULLS LAST,
                        ai.award_amount_info_id DESC
                    LIMIT 1
                ) amt ON TRUE
                WHERE funding.subaward_id = :subawardId
                ORDER BY funding.award_id NULLS LAST,
                    funding.subaward_funding_source_id
                """)
                .param("subawardId", subawardId)
                .query(SubawardFundingResponse.class)
                .list();
    }

    public List<SubawardAttachmentResponse> findAttachments(long subawardId) {
        return jdbc.sql("""
                SELECT
                    attachment.attachment_id,
                    attachment.subaward_id,
                    attachment.subaward_code,
                    attachment.sequence_number,
                    attachment.attachment_type_code,
                    attachment.attachment_type_description,
                    attachment.document_id,
                    attachment.file_name,
                    attachment.mime_type,
                    attachment.document_status_code,
                    attachment.description,
                    attachment.last_update_timestamp,
                    attachment.last_update_user,
                    attachment.source_update_timestamp,
                    attachment.source_update_user,
                    attachment.source_version_number,
                    attachment.source_object_id,
                    archived.attachment_id IS NOT NULL AS archived
                FROM archive.subaward_attachment attachment
                LEFT JOIN archive.subaward_attachment_archive archived
                  ON archived.attachment_id = attachment.attachment_id
                 AND archived.subaward_id = attachment.subaward_id
                 AND archived.archive_status = 'ARCHIVED'
                WHERE attachment.subaward_id = :subawardId
                ORDER BY attachment.attachment_id
                """)
                .param("subawardId", subawardId)
                .query(SubawardAttachmentResponse.class)
                .list();
    }

    public Optional<Long> findAttachmentSubawardId(long attachmentId) {
        return jdbc.sql("""
                SELECT subaward_id
                FROM archive.subaward_attachment
                WHERE attachment_id = :attachmentId
                """)
                .param("attachmentId", attachmentId)
                .query(Long.class)
                .optional();
    }

    public Optional<SubawardArchivedAttachment> findArchivedAttachment(
            long subawardId,
            long attachmentId
    ) {
        return jdbc.sql("""
                SELECT
                    attachment_id,
                    subaward_id,
                    original_file_name,
                    mime_type,
                    s3_bucket,
                    s3_key,
                    byte_size,
                    archive_status
                FROM archive.subaward_attachment_archive
                WHERE attachment_id = :attachmentId
                  AND subaward_id = :subawardId
                """)
                .param("attachmentId", attachmentId)
                .param("subawardId", subawardId)
                .query(SubawardArchivedAttachment.class)
                .optional();
    }

    public Optional<SubawardTemplateInfoResponse> findTemplateInfo(
            long subawardId
    ) {
        return jdbc.sql("""
                SELECT
                    subaward_id,
                    subaward_code,
                    sequence_number,
                    sow_or_sub_proposal_budget,
                    sub_proposal_date,
                    invoice_or_payment_contact,
                    irb_iacuc_contact,
                    final_stmt_of_costs_contact,
                    change_requests_contact,
                    sub_change_requests_contact,
                    termination_contact,
                    sub_termination_contact,
                    no_cost_extension_contact,
                    perf_site_diff_from_org_addr,
                    perf_site_same_as_sub_pi_addr,
                    sub_registered_in_ccr,
                    sub_exempt_from_reporting_comp,
                    parent_duns_number,
                    parent_congressional_district,
                    exempt_from_rprtg_exec_comp,
                    copyright_type,
                    automatic_carry_forward,
                    carry_forward_requests_sent_to,
                    treatment_prgm_income_additive,
                    applicable_program_regulations,
                    applicable_program_regs_date,
                    mpi_award,
                    mpi_leadership_plan,
                    r_and_d,
                    includes_cost_sharing,
                    fcio,
                    invoices_emailed,
                    invoice_address_diff,
                    invoice_email_diff,
                    fcio_subrec_policy_cd,
                    animal_flag,
                    animal_pte_send_cd,
                    animal_pte_nr_cd,
                    human_flag,
                    human_subjects,
                    human_exempt_docs,
                    human_pte_send_cd,
                    human_pte_nr_cd,
                    human_data_exchange_agree_cd,
                    human_data_exchange_terms_cd,
                    human_includes_clinical_trials,
                    additional_terms,
                    treatment_of_income,
                    data_sharing_attachment,
                    data_sharing_cd,
                    final_statement_due_cd,
                    source_update_timestamp,
                    source_update_user
                FROM archive.subaward_template_info
                WHERE subaward_id = :subawardId
                """)
                .param("subawardId", subawardId)
                .query(SubawardTemplateInfoResponse.class)
                .optional();
    }

    public List<SubawardCloseoutResponse> findCloseout(long subawardId) {
        return jdbc.sql("""
                SELECT
                    subaward_closeout_id,
                    subaward_id,
                    subaward_code,
                    sequence_number,
                    closeout_number,
                    closeout_type_code,
                    date_requested,
                    date_followup,
                    date_received,
                    comments,
                    source_update_timestamp,
                    source_update_user,
                    source_version_number,
                    source_object_id
                FROM archive.subaward_closeout
                WHERE subaward_id = :subawardId
                ORDER BY closeout_number NULLS LAST, subaward_closeout_id
                """)
                .param("subawardId", subawardId)
                .query(SubawardCloseoutResponse.class)
                .list();
    }

    public List<SubawardReportResponse> findReports(long subawardId) {
        return jdbc.sql("""
                SELECT
                    subaward_report_id,
                    subaward_id,
                    subaward_code,
                    sequence_number,
                    report_type_code,
                    report_type_description,
                    source_update_timestamp,
                    source_update_user,
                    source_version_number,
                    source_object_id
                FROM archive.subaward_report
                WHERE subaward_id = :subawardId
                ORDER BY subaward_report_id
                """)
                .param("subawardId", subawardId)
                .query(SubawardReportResponse.class)
                .list();
    }

    public List<SubawardNotepadResponse> findNotepad(long subawardId) {
        return jdbc.sql("""
                SELECT
                    subaward_notepad_id,
                    subaward_id,
                    subaward_code,
                    entry_number,
                    note_topic,
                    comments,
                    restricted_view,
                    create_timestamp,
                    create_user,
                    source_update_timestamp,
                    source_update_user,
                    source_version_number,
                    source_object_id
                FROM archive.subaward_notepad
                WHERE subaward_id = :subawardId
                ORDER BY entry_number NULLS LAST, subaward_notepad_id
                """)
                .param("subawardId", subawardId)
                .query(SubawardNotepadResponse.class)
                .list();
    }

    public List<SubawardNotificationResponse> findNotifications(
            long subawardId
    ) {
        return jdbc.sql("""
                SELECT
                    notification_id,
                    owning_document_id_fk,
                    document_number,
                    subaward_code,
                    notification_type_id,
                    recipients,
                    subject,
                    message,
                    create_timestamp,
                    source_update_timestamp,
                    source_update_user,
                    source_version_number,
                    source_object_id
                FROM archive.subaward_notification
                WHERE owning_document_id_fk = :subawardId
                ORDER BY
                    create_timestamp DESC NULLS LAST,
                    notification_id DESC
                """)
                .param("subawardId", subawardId)
                .query(SubawardNotificationResponse.class)
                .list();
    }

    private String normalizeQuery(String query) {
        return query == null ? "" : query.trim();
    }
}
