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
        String filter = normalized.isEmpty()
                ? ""
                : """
                WHERE EXISTS (
                        SELECT 1
                        FROM archive.protocol_version version
                        WHERE version.protocol_number =
                              family.protocol_number
                          AND (
                               version.protocol_number
                                   ILIKE '%' || :query || '%'
                            OR COALESCE(version.document_number, '')
                                   ILIKE '%' || :query || '%'
                            OR COALESCE(version.title, '')
                                   ILIKE '%' || :query || '%'
                            OR COALESCE(version.description, '')
                                   ILIKE '%' || :query || '%'
                            OR COALESCE(
                                   version.protocol_status_description,
                                   ''
                               ) ILIKE '%' || :query || '%'
                            OR COALESCE(
                                   version.protocol_type_description,
                                   ''
                               ) ILIKE '%' || :query || '%'
                            OR COALESCE(
                                   version.fda_application_number,
                                   ''
                               ) ILIKE '%' || :query || '%'
                            OR COALESCE(version.reference_number_1, '')
                                   ILIKE '%' || :query || '%'
                            OR COALESCE(version.reference_number_2, '')
                                   ILIKE '%' || :query || '%'
                          )
                   )
                   OR EXISTS (
                        SELECT 1
                        FROM archive.protocol_person person
                        WHERE person.protocol_number =
                              family.protocol_number
                          AND (
                               COALESCE(person.person_name, '')
                                   ILIKE '%' || :query || '%'
                            OR COALESCE(person.person_id, '')
                                   ILIKE '%' || :query || '%'
                            OR COALESCE(
                                   CAST(person.rolodex_id AS TEXT),
                                   ''
                               ) ILIKE '%' || :query || '%'
                          )
                   )
                   OR EXISTS (
                        SELECT 1
                        FROM archive.protocol_unit unit_row
                        WHERE unit_row.protocol_number =
                              family.protocol_number
                          AND COALESCE(unit_row.unit_number, '')
                              ILIKE '%' || :query || '%'
                   )
                   OR EXISTS (
                        SELECT 1
                        FROM archive.protocol_funding funding
                        WHERE funding.protocol_number =
                              family.protocol_number
                          AND (
                               COALESCE(
                                   funding.funding_source_number,
                                   ''
                               ) ILIKE '%' || :query || '%'
                            OR COALESCE(
                                   funding.funding_source_name,
                                   ''
                               ) ILIKE '%' || :query || '%'
                          )
                   )
                   OR EXISTS (
                        SELECT 1
                        FROM archive.protocol_research_area research_area
                        WHERE research_area.protocol_number =
                              family.protocol_number
                          AND COALESCE(
                                  research_area.research_area_code,
                                  ''
                              ) ILIKE '%' || :query || '%'
                   )
                   OR EXISTS (
                        SELECT 1
                        FROM archive.protocol_location location
                        WHERE location.protocol_number =
                              family.protocol_number
                          AND (
                               COALESCE(location.organization_id, '')
                                   ILIKE '%' || :query || '%'
                            OR COALESCE(
                                   CAST(location.rolodex_id AS TEXT),
                                   ''
                               ) ILIKE '%' || :query || '%'
                          )
                   )
                   OR EXISTS (
                        SELECT 1
                        FROM archive.protocol_submission submission
                        WHERE submission.protocol_number =
                              family.protocol_number
                          AND (
                               COALESCE(submission.schedule_id, '')
                                   ILIKE '%' || :query || '%'
                            OR COALESCE(submission.committee_id, '')
                                   ILIKE '%' || :query || '%'
                            OR COALESCE(
                                   submission.submission_type_code,
                                   ''
                               ) ILIKE '%' || :query || '%'
                            OR COALESCE(
                                   submission.submission_status_code,
                                   ''
                               ) ILIKE '%' || :query || '%'
                            OR COALESCE(submission.comments, '')
                                   ILIKE '%' || :query || '%'
                          )
                   )
                   OR EXISTS (
                        SELECT 1
                        FROM archive.protocol_action action
                        WHERE action.protocol_number =
                              family.protocol_number
                          AND (
                               COALESCE(
                                   action.protocol_action_type_code,
                                   ''
                               ) ILIKE '%' || :query || '%'
                            OR COALESCE(action.comments, '')
                                   ILIKE '%' || :query || '%'
                          )
                   )
                   OR EXISTS (
                        SELECT 1
                        FROM archive.protocol_amend_renewal amend_renewal
                        WHERE amend_renewal.protocol_number =
                              family.protocol_number
                          AND (
                               COALESCE(
                                   amend_renewal.proto_amend_ren_number,
                                   ''
                               ) ILIKE '%' || :query || '%'
                            OR COALESCE(amend_renewal.summary, '')
                                   ILIKE '%' || :query || '%'
                          )
                   )
                """;
        JdbcClient.StatementSpec statement = jdbc.sql("""
                WITH filtered AS MATERIALIZED (
                    SELECT
                        family.protocol_number,
                        family.version_count,
                        family.latest_protocol_id,
                        family.latest_sequence_number,
                        family.title,
                        family.protocol_status_description,
                        family.protocol_type_description,
                        family.active,
                        family.expiration_date
                    FROM archive.v_protocol_family family
                """ + filter + """
                ),
                page_rows AS (
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
                    FROM filtered
                    ORDER BY protocol_number
                    LIMIT :size OFFSET :offset
                ),
                total AS (
                    SELECT COUNT(*) AS total_elements
                    FROM filtered
                )
                SELECT
                    total.total_elements,
                    page_rows.protocol_number,
                    page_rows.version_count,
                    page_rows.latest_protocol_id,
                    page_rows.latest_sequence_number,
                    page_rows.title,
                    page_rows.protocol_status_description,
                    page_rows.protocol_type_description,
                    page_rows.active,
                    page_rows.expiration_date
                FROM total
                LEFT JOIN page_rows ON TRUE
                ORDER BY page_rows.protocol_number
                """);
        if (!normalized.isEmpty()) {
            statement = statement.param("query", normalized);
        }
        List<FamilySearchRow> rows = statement
                .param("size", size)
                .param("offset", page * size)
                .query(FamilySearchRow.class)
                .list();
        long total = rows.getFirst().totalElements();
        List<ProtocolSummaryResponse> content = rows.stream()
                .filter(FamilySearchRow::hasProtocol)
                .map(FamilySearchRow::toResponse)
                .toList();
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

    record FamilySearchRow(
            long totalElements,
            String protocolNumber,
            Long versionCount,
            Long latestProtocolId,
            Integer latestSequenceNumber,
            String title,
            String protocolStatusDescription,
            String protocolTypeDescription,
            String active,
            java.time.LocalDate expirationDate
    ) {
        boolean hasProtocol() {
            return protocolNumber != null;
        }

        ProtocolSummaryResponse toResponse() {
            return new ProtocolSummaryResponse(
                    protocolNumber,
                    versionCount,
                    latestProtocolId,
                    latestSequenceNumber,
                    title,
                    protocolStatusDescription,
                    protocolTypeDescription,
                    active,
                    expirationDate
            );
        }
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
