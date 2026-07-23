package edu.bu.archive.adapter.out.persistence;

import edu.bu.archive.adapter.in.web.dto.PageResponse;
import edu.bu.archive.adapter.in.web.dto.protocol.ProtocolActionResponse;
import edu.bu.archive.adapter.in.web.dto.protocol.ProtocolAmendRenewalResponse;
import edu.bu.archive.adapter.in.web.dto.protocol.ProtocolFundingResponse;
import edu.bu.archive.adapter.in.web.dto.protocol.ProtocolLocationResponse;
import edu.bu.archive.adapter.in.web.dto.protocol.ProtocolPersonResponse;
import edu.bu.archive.adapter.in.web.dto.protocol.ProtocolResearchAreaResponse;
import edu.bu.archive.adapter.in.web.dto.protocol.ProtocolSubmissionResponse;
import edu.bu.archive.adapter.in.web.dto.protocol.ProtocolSummaryResponse;
import edu.bu.archive.adapter.in.web.dto.protocol.ProtocolUnitResponse;
import edu.bu.archive.adapter.in.web.dto.protocol.ProtocolVersionResponse;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

@Repository
public class ProtocolArchiveRepository {

    private final JdbcClient jdbc;

    public ProtocolArchiveRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public PageResponse<ProtocolSummaryResponse> findFamilies(
            String query,
            int page,
            int size
    ) {
        String normalized = query == null ? "" : query.trim();
        String filter = """
                WHERE :query = ''
                   OR protocol_number ILIKE '%' || :query || '%'
                   OR COALESCE(title, '') ILIKE '%' || :query || '%'
                """;
        long total = jdbc.sql(
                        "SELECT COUNT(*) FROM archive.v_protocol_family "
                                + filter
                )
                .param("query", normalized)
                .query(Long.class)
                .single();
        List<ProtocolSummaryResponse> content = jdbc.sql("""
                SELECT
                    protocol_number,
                    version_count,
                    latest_protocol_id,
                    latest_sequence_number,
                    title,
                    protocol_status_description,
                    protocol_type_description,
                    active,
                    expiration_date
                FROM archive.v_protocol_family
                """ + filter + """
                ORDER BY protocol_number
                LIMIT :size OFFSET :offset
                """)
                .param("query", normalized)
                .param("size", size)
                .param("offset", page * size)
                .query(ProtocolSummaryResponse.class)
                .list();
        int pages = total == 0
                ? 0
                : (int) Math.ceil((double) total / size);
        return new PageResponse<>(
                content,
                page,
                size,
                total,
                pages,
                page == 0,
                pages == 0 || page >= pages - 1
        );
    }

    public List<ProtocolVersionResponse> findHistory(
            String protocolNumber
    ) {
        return jdbc.sql(VERSION_SELECT + """
                WHERE protocol_number = :protocolNumber
                ORDER BY
                    sequence_number DESC,
                    source_update_timestamp DESC NULLS LAST,
                    protocol_id DESC
                """)
                .param("protocolNumber", protocolNumber)
                .query(ProtocolVersionResponse.class)
                .list();
    }

    public Optional<ProtocolVersionResponse> findVersion(long protocolId) {
        return jdbc.sql(VERSION_SELECT + """
                WHERE protocol_id = :protocolId
                """)
                .param("protocolId", protocolId)
                .query(ProtocolVersionResponse.class)
                .optional();
    }

    public List<ProtocolPersonResponse> findPersonnel(long protocolId) {
        List<ProtocolUnitResponse> units = jdbc.sql("""
                SELECT
                    unit_row.protocol_units_id,
                    unit_row.protocol_person_id,
                    unit_row.protocol_number,
                    unit_row.sequence_number,
                    unit_row.unit_number,
                    unit_row.lead_unit_flag,
                    unit_row.person_id,
                    unit_row.source_update_timestamp,
                    unit_row.source_update_user,
                    unit_row.source_version_number,
                    unit_row.source_object_id
                FROM archive.protocol_unit unit_row
                JOIN archive.protocol_person person
                  ON person.protocol_person_id =
                     unit_row.protocol_person_id
                WHERE person.protocol_id = :protocolId
                ORDER BY
                    unit_row.protocol_person_id,
                    unit_row.lead_unit_flag DESC NULLS LAST,
                    unit_row.protocol_units_id
                """)
                .param("protocolId", protocolId)
                .query(ProtocolUnitResponse.class)
                .list();
        Map<Long, List<ProtocolUnitResponse>> unitsByPerson =
                units.stream().collect(
                        Collectors.groupingBy(
                                ProtocolUnitResponse::protocolPersonId
                        )
                );

        return jdbc.sql("""
                SELECT
                    protocol_person_id,
                    protocol_id,
                    source_protocol_id,
                    protocol_number,
                    sequence_number,
                    person_id,
                    person_name,
                    protocol_person_role_id,
                    rolodex_id,
                    affiliation_type_code,
                    comments,
                    source_update_timestamp,
                    source_update_user,
                    source_version_number,
                    source_object_id
                FROM archive.protocol_person
                WHERE protocol_id = :protocolId
                ORDER BY
                    protocol_person_role_id NULLS LAST,
                    person_name NULLS LAST,
                    protocol_person_id
                """)
                .param("protocolId", protocolId)
                .query((resultSet, rowNumber) -> {
                    long personId = resultSet.getLong(
                            "protocol_person_id"
                    );
                    return new ProtocolPersonResponse(
                            personId,
                            resultSet.getLong("protocol_id"),
                            resultSet.getObject(
                                    "source_protocol_id",
                                    Long.class
                            ),
                            resultSet.getString("protocol_number"),
                            resultSet.getInt("sequence_number"),
                            resultSet.getString("person_id"),
                            resultSet.getString("person_name"),
                            resultSet.getString(
                                    "protocol_person_role_id"
                            ),
                            resultSet.getObject(
                                    "rolodex_id",
                                    Long.class
                            ),
                            resultSet.getString(
                                    "affiliation_type_code"
                            ),
                            resultSet.getString("comments"),
                            resultSet.getObject(
                                    "source_update_timestamp",
                                    java.time.LocalDateTime.class
                            ),
                            resultSet.getString(
                                    "source_update_user"
                            ),
                            resultSet.getObject(
                                    "source_version_number",
                                    Long.class
                            ),
                            resultSet.getString("source_object_id"),
                            unitsByPerson.getOrDefault(
                                    personId,
                                    List.of()
                            )
                    );
                })
                .list();
    }

    public List<ProtocolFundingResponse> findFunding(long protocolId) {
        return jdbc.sql("""
                SELECT
                    protocol_funding_source_id,
                    protocol_id,
                    source_protocol_id,
                    protocol_number,
                    sequence_number,
                    funding_source_type_code,
                    funding_source_number,
                    funding_source_name,
                    source_update_timestamp,
                    source_update_user,
                    source_version_number,
                    source_object_id
                FROM archive.protocol_funding
                WHERE protocol_id = :protocolId
                ORDER BY
                    funding_source_type_code NULLS LAST,
                    funding_source_name NULLS LAST,
                    funding_source_number NULLS LAST,
                    protocol_funding_source_id
                """)
                .param("protocolId", protocolId)
                .query(ProtocolFundingResponse.class)
                .list();
    }

    public List<ProtocolResearchAreaResponse> findResearchAreas(
            long protocolId
    ) {
        return jdbc.sql("""
                SELECT
                    protocol_research_area_id,
                    protocol_id,
                    source_protocol_id,
                    protocol_number,
                    sequence_number,
                    research_area_code,
                    source_update_timestamp,
                    source_update_user,
                    source_version_number,
                    source_object_id
                FROM archive.protocol_research_area
                WHERE protocol_id = :protocolId
                ORDER BY
                    research_area_code NULLS LAST,
                    protocol_research_area_id
                """)
                .param("protocolId", protocolId)
                .query(ProtocolResearchAreaResponse.class)
                .list();
    }

    public List<ProtocolLocationResponse> findLocations(long protocolId) {
        return jdbc.sql("""
                SELECT
                    protocol_location_id,
                    protocol_id,
                    source_protocol_id,
                    protocol_number,
                    sequence_number,
                    parent_resolution_method,
                    protocol_org_type_code,
                    organization_id,
                    rolodex_id,
                    source_update_timestamp,
                    source_update_user,
                    source_version_number,
                    source_object_id
                FROM archive.protocol_location
                WHERE protocol_id = :protocolId
                ORDER BY
                    protocol_org_type_code NULLS LAST,
                    organization_id NULLS LAST,
                    rolodex_id NULLS LAST,
                    protocol_location_id
                """)
                .param("protocolId", protocolId)
                .query(ProtocolLocationResponse.class)
                .list();
    }

    public List<ProtocolSubmissionResponse> findSubmissions(
            long protocolId
    ) {
        return jdbc.sql("""
                SELECT
                    submission_id,
                    protocol_id,
                    source_protocol_id,
                    protocol_number,
                    sequence_number,
                    submission_number,
                    schedule_id,
                    committee_id,
                    submission_type_code,
                    submission_type_qual_code,
                    submission_status_code,
                    schedule_id_fk,
                    committee_id_fk,
                    protocol_review_type_code,
                    submission_date,
                    comments,
                    comm_decision_motion_type_code,
                    yes_vote_count,
                    no_vote_count,
                    abstainer_count,
                    recused_count,
                    voting_comments,
                    is_billable,
                    source_update_timestamp,
                    source_update_user,
                    source_version_number,
                    source_object_id
                FROM archive.protocol_submission
                WHERE protocol_id = :protocolId
                ORDER BY
                    submission_date NULLS LAST,
                    submission_number NULLS LAST,
                    submission_id
                """)
                .param("protocolId", protocolId)
                .query(ProtocolSubmissionResponse.class)
                .list();
    }

    public List<ProtocolActionResponse> findActions(long protocolId) {
        return jdbc.sql("""
                SELECT
                    protocol_action_id,
                    action_id,
                    protocol_id,
                    source_protocol_id,
                    protocol_number,
                    sequence_number,
                    submission_number,
                    submission_id_fk,
                    protocol_action_type_code,
                    comments,
                    prev_submission_status_code,
                    submission_type_code,
                    prev_protocol_status_code,
                    source_create_timestamp,
                    source_create_user,
                    source_update_timestamp,
                    source_update_user,
                    action_date,
                    actual_action_date,
                    source_version_number,
                    source_object_id,
                    followup_action_code
                FROM archive.protocol_action
                WHERE protocol_id = :protocolId
                ORDER BY
                    action_date NULLS LAST,
                    actual_action_date NULLS LAST,
                    action_id NULLS LAST,
                    protocol_action_id
                """)
                .param("protocolId", protocolId)
                .query(ProtocolActionResponse.class)
                .list();
    }

    public List<ProtocolAmendRenewalResponse> findAmendRenewals(
            long protocolId
    ) {
        return jdbc.sql("""
                SELECT
                    proto_amend_renewal_id,
                    protocol_id,
                    source_protocol_id,
                    protocol_number,
                    sequence_number,
                    proto_amend_ren_number,
                    date_created,
                    summary,
                    source_update_timestamp,
                    source_update_user,
                    source_version_number,
                    source_object_id
                FROM archive.protocol_amend_renewal
                WHERE protocol_id = :protocolId
                ORDER BY
                    date_created NULLS LAST,
                    proto_amend_ren_number NULLS LAST,
                    proto_amend_renewal_id
                """)
                .param("protocolId", protocolId)
                .query(ProtocolAmendRenewalResponse.class)
                .list();
    }

    private static final String VERSION_SELECT = """
            SELECT
                protocol_id,
                protocol_number,
                sequence_number,
                document_number,
                active,
                protocol_type_code,
                protocol_type_description,
                protocol_status_code,
                protocol_status_description,
                title,
                description,
                initial_submission_date,
                approval_date,
                expiration_date,
                last_approval_date,
                source_update_timestamp,
                source_update_user
            FROM archive.protocol_version
            """;
}
