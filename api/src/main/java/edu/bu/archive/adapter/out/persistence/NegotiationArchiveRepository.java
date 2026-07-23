package edu.bu.archive.adapter.out.persistence;

import edu.bu.archive.adapter.in.web.dto.negotiation.NegotiationActivityResponse;
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

        Long count = jdbc.sql("""
                SELECT COUNT(*)
                FROM archive.negotiation
                WHERE :query = ''
                   OR CAST(negotiation_id AS TEXT)
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
                """)
                .param("query", normalizedQuery)
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
                    associated_document_id,
                    negotiator_person_id,
                    negotiator_full_name,
                    negotiation_start_date,
                    negotiation_end_date,
                    anticipated_award_date
                FROM archive.negotiation
                WHERE :query = ''
                   OR CAST(negotiation_id AS TEXT)
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
                ORDER BY
                    source_update_timestamp DESC NULLS LAST,
                    negotiation_id DESC
                LIMIT :limit
                OFFSET :offset
                """)
                .param("query", normalizedQuery)
                .param("limit", limit)
                .param("offset", offset)
                .query(NegotiationSummaryResponse.class)
                .list();
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
                    negotiation_custom_data_id,
                    negotiation_id,
                    negotiation_number,
                    custom_attribute_id,
                    value,
                    source_update_timestamp,
                    source_update_user,
                    source_version_number,
                    source_object_id
                FROM archive.negotiation_custom_data
                WHERE negotiation_id = :negotiationId
                ORDER BY negotiation_custom_data_id
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

    private String normalizeQuery(String query) {
        return query == null ? "" : query.trim();
    }
}
