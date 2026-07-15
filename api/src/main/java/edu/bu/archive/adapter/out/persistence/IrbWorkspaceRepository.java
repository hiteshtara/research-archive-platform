package edu.bu.archive.adapter.out.persistence;

import edu.bu.archive.adapter.in.web.dto.workspace.IrbFundingResponse;
import edu.bu.archive.adapter.in.web.dto.workspace.IrbProtocolVersionResponse;
import edu.bu.archive.adapter.in.web.dto.workspace.IrbSubmissionResponse;
import edu.bu.archive.adapter.in.web.dto.workspace.IrbTimelineResponse;
import edu.bu.archive.adapter.in.web.dto.workspace.IrbWorkspaceResponse;
import edu.bu.archive.exception.RecordNotFoundException;

import java.sql.Date;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
@Transactional(readOnly = true)
public class IrbWorkspaceRepository {

    private final JdbcClient jdbcClient;

    public IrbWorkspaceRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    public IrbWorkspaceResponse findByArchiveRecordId(Long recordId) {
        CurrentProtocol current = findCurrentProtocol(recordId);

        IrbProtocolVersionResponse protocol =
                findHistoricalProtocol(current.protocolBase())
                        .orElseGet(() -> toFallbackProtocol(current));

        return new IrbWorkspaceResponse(
                protocol,
                findFunding(current.protocolBase()),
                findSubmissions(current.protocolBase()),
                findTimeline(current.protocolBase())
        );
    }

    private CurrentProtocol findCurrentProtocol(Long recordId) {
        return jdbcClient.sql("""
                SELECT
                    record_id,
                    protocol_id,
                    study_id,
                    protocol_base,
                    protocol_number,
                    protocol_type,
                    protocol_status,
                    approval_date,
                    pi_buid,
                    pi_full_name,
                    pi_email
                FROM archive.irb_protocol
                WHERE record_id = :recordId
                """)
                .param("recordId", recordId)
                .query((rs, rowNumber) ->
                        new CurrentProtocol(
                                rs.getLong("record_id"),
                                rs.getObject("protocol_id", Long.class),
                                rs.getString("study_id"),
                                rs.getString("protocol_base"),
                                rs.getString("protocol_number"),
                                rs.getString("protocol_type"),
                                rs.getString("protocol_status"),
                                toLocalDate(rs.getDate("approval_date")),
                                rs.getString("pi_buid"),
                                rs.getString("pi_full_name"),
                                rs.getString("pi_email")
                        )
                )
                .optional()
                .orElseThrow(() ->
                        new RecordNotFoundException(
                                "IRB record not found: " + recordId
                        )
                );
    }

    private Optional<IrbProtocolVersionResponse> findHistoricalProtocol(
            String protocolBase
    ) {
        return jdbcClient.sql("""
                SELECT
                    protocol_id,
                    protocol_base,
                    protocol_number,
                    sequence_number,
                    crc_protocol_num,
                    document_number,
                    title,
                    protocol_type,
                    protocol_status,
                    ohrp_categories,
                    summary_keywords,
                    pi_id,
                    pi_email,
                    pi_affiliation,
                    fund_center_number,
                    school_number,
                    irb_analyst_id,
                    irb_advisor_id,
                    received_date,
                    claimed_date,
                    determination_date,
                    approval_date,
                    expiration_date,
                    closure_date,
                    authorization_date,
                    record_storage_box,
                    expiration_status,
                    working_days,
                    calendar_days,
                    irb_days,
                    pi_days,
                    funding_source_count
                FROM archive.irb_protocol_version
                WHERE TRIM(protocol_base) = TRIM(:protocolBase)
                ORDER BY
                    sequence_number DESC NULLS LAST,
                    protocol_id DESC
                LIMIT 1
                """)
                .param("protocolBase", protocolBase)
                .query((rs, rowNumber) ->
                        new IrbProtocolVersionResponse(
                                rs.getLong("protocol_id"),
                                rs.getString("protocol_base"),
                                rs.getString("protocol_number"),
                                rs.getObject("sequence_number", Integer.class),
                                rs.getString("crc_protocol_num"),
                                rs.getString("document_number"),
                                rs.getString("title"),
                                rs.getString("protocol_type"),
                                rs.getString("protocol_status"),
                                rs.getString("ohrp_categories"),
                                rs.getString("summary_keywords"),
                                rs.getString("pi_id"),
                                rs.getString("pi_email"),
                                rs.getString("pi_affiliation"),
                                rs.getString("fund_center_number"),
                                rs.getString("school_number"),
                                rs.getString("irb_analyst_id"),
                                rs.getString("irb_advisor_id"),
                                toLocalDate(rs.getDate("received_date")),
                                toLocalDate(rs.getDate("claimed_date")),
                                toLocalDate(rs.getDate("determination_date")),
                                toLocalDate(rs.getDate("approval_date")),
                                toLocalDate(rs.getDate("expiration_date")),
                                toLocalDate(rs.getDate("closure_date")),
                                toLocalDate(rs.getDate("authorization_date")),
                                rs.getString("record_storage_box"),
                                rs.getString("expiration_status"),
                                rs.getObject("working_days", Integer.class),
                                rs.getObject("calendar_days", Integer.class),
                                rs.getObject("irb_days", Integer.class),
                                rs.getObject("pi_days", Integer.class),
                                rs.getObject(
                                        "funding_source_count",
                                        Integer.class
                                )
                        )
                )
                .optional();
    }

    private List<IrbFundingResponse> findFunding(String protocolBase) {
        return jdbcClient.sql("""
                SELECT DISTINCT
                    funding_sequence,
                    funding_source
                FROM archive.irb_funding_source
                WHERE TRIM(protocol_base) = TRIM(:protocolBase)
                ORDER BY
                    funding_sequence NULLS LAST,
                    funding_source
                """)
                .param("protocolBase", protocolBase)
                .query((rs, rowNumber) ->
                        new IrbFundingResponse(
                                rs.getObject(
                                        "funding_sequence",
                                        Integer.class
                                ),
                                rs.getString("funding_source")
                        )
                )
                .list();
    }

    private List<IrbSubmissionResponse> findSubmissions(
            String protocolBase
    ) {
        return jdbcClient.sql("""
                SELECT
                    sequence_number,
                    submission_number,
                    submission_type,
                    submission_status,
                    event_type,
                    review_type
                FROM archive.irb_submission
                WHERE TRIM(protocol_base) = TRIM(:protocolBase)
                ORDER BY
                    sequence_number DESC NULLS LAST,
                    submission_number DESC NULLS LAST
                """)
                .param("protocolBase", protocolBase)
                .query((rs, rowNumber) ->
                        new IrbSubmissionResponse(
                                rs.getObject(
                                        "sequence_number",
                                        Integer.class
                                ),
                                rs.getObject(
                                        "submission_number",
                                        Integer.class
                                ),
                                rs.getString("submission_type"),
                                rs.getString("submission_status"),
                                rs.getString("event_type"),
                                rs.getString("review_type")
                        )
                )
                .list();
    }

    private List<IrbTimelineResponse> findTimeline(
            String protocolBase
    ) {
        return jdbcClient.sql("""
                SELECT DISTINCT
                    event_date,
                    event_type,
                    event_sequence
                FROM archive.irb_timeline_event
                WHERE TRIM(protocol_base) = TRIM(:protocolBase)
                ORDER BY
                    event_date DESC,
                    event_sequence DESC NULLS LAST,
                    event_type
                """)
                .param("protocolBase", protocolBase)
                .query((rs, rowNumber) ->
                        new IrbTimelineResponse(
                                toLocalDate(rs.getDate("event_date")),
                                rs.getString("event_type"),
                                rs.getObject(
                                        "event_sequence",
                                        Integer.class
                                )
                        )
                )
                .list();
    }

    private IrbProtocolVersionResponse toFallbackProtocol(
            CurrentProtocol current
    ) {
        Long fallbackProtocolId =
                current.protocolId() != null
                        ? current.protocolId()
                        : current.recordId();

        return new IrbProtocolVersionResponse(
                fallbackProtocolId,
                current.protocolBase(),
                current.protocolNumber(),
                null,
                null,
                null,
                null,
                current.protocolType(),
                current.protocolStatus(),
                null,
                null,
                current.piBuid(),
                current.piEmail(),
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                current.approvalDate(),
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null
        );
    }

    private static LocalDate toLocalDate(Date date) {
        return date == null ? null : date.toLocalDate();
    }

    private record CurrentProtocol(
            Long recordId,
            Long protocolId,
            String studyId,
            String protocolBase,
            String protocolNumber,
            String protocolType,
            String protocolStatus,
            LocalDate approvalDate,
            String piBuid,
            String piFullName,
            String piEmail
    ) {
    }
}
