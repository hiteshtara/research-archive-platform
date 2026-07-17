package edu.bu.archive.adapter.out.persistence;

import edu.bu.archive.adapter.in.web.dto.InvestigatorProfileResponse;
import edu.bu.archive.adapter.in.web.dto.InvestigatorStudyResponse;

import java.time.LocalDate;
import java.util.List;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import static org.springframework.http.HttpStatus.NOT_FOUND;

@Repository
@Transactional(readOnly = true)
public class InvestigatorRepository {

    private final JdbcClient jdbcClient;

    public InvestigatorRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    public InvestigatorProfileResponse findByEmail(String email) {
        String normalizedEmail = email.trim();

        InvestigatorIdentity identity = jdbcClient.sql("""
                SELECT
                    COALESCE(
                        NULLIF(TRIM(global_search.pi_full_name), ''),
                        'Name unavailable'
                    ) AS investigator_name,
                    UPPER(TRIM(global_search.pi_email)) AS investigator_email,
                    (
                        SELECT protocol.pi_id
                        FROM archive.irb_protocol_version protocol
                        WHERE UPPER(TRIM(protocol.pi_email)) =
                              UPPER(TRIM(:email))
                          AND NULLIF(TRIM(protocol.pi_id), '') IS NOT NULL
                        ORDER BY protocol.protocol_id DESC
                        LIMIT 1
                    ) AS investigator_buid
                FROM archive.v_global_search global_search
                WHERE UPPER(TRIM(global_search.pi_email)) =
                      UPPER(TRIM(:email))
                ORDER BY global_search.record_id NULLS LAST
                LIMIT 1
                """)
                .param("email", normalizedEmail)
                .query((resultSet, rowNumber) ->
                        new InvestigatorIdentity(
                                resultSet.getString("investigator_name"),
                                resultSet.getString("investigator_email"),
                                resultSet.getString("investigator_buid")
                        )
                )
                .optional()
                .orElseGet(() ->
                        jdbcClient.sql("""
                                SELECT
                                    'Name unavailable' AS investigator_name,
                                    UPPER(TRIM(pi_email)) AS investigator_email,
                                    pi_id AS investigator_buid
                                FROM archive.irb_protocol_version
                                WHERE UPPER(TRIM(pi_email)) =
                                      UPPER(TRIM(:email))
                                ORDER BY protocol_id DESC
                                LIMIT 1
                                """)
                                .param("email", normalizedEmail)
                                .query((resultSet, rowNumber) ->
                                        new InvestigatorIdentity(
                                                resultSet.getString(
                                                        "investigator_name"
                                                ),
                                                resultSet.getString(
                                                        "investigator_email"
                                                ),
                                                resultSet.getString(
                                                        "investigator_buid"
                                                )
                                        )
                                )
                                .optional()
                                .orElseThrow(() ->
                                        new ResponseStatusException(
                                                NOT_FOUND,
                                                "Investigator was not found."
                                        )
                                )
                );

        List<InvestigatorStudyResponse> currentStudies = jdbcClient.sql("""
                SELECT
                    record_id,
                    protocol_id,
                    protocol_base,
                    protocol_number,
                    title,
                    protocol_status,
                    protocol_type,
                    NULL::date AS approval_date
                FROM archive.v_global_search
                WHERE module = 'IRB'
                  AND UPPER(TRIM(pi_email)) =
                      UPPER(TRIM(:email))
                  AND record_id IS NOT NULL
                ORDER BY
                    title NULLS LAST,
                    protocol_base
                """)
                .param("email", normalizedEmail)
                .query((resultSet, rowNumber) ->
                        mapStudy(resultSet)
                )
                .list();

        List<InvestigatorStudyResponse> historicalStudies = jdbcClient.sql("""
                WITH matching_families AS (
                    SELECT DISTINCT protocol_base
                    FROM archive.irb_protocol_version
                    WHERE UPPER(TRIM(pi_email)) =
                          UPPER(TRIM(:email))
                ),
                ranked_versions AS (
                    SELECT
                        protocol.protocol_id,
                        protocol.protocol_base,
                        protocol.protocol_number,
                        protocol.title,
                        protocol.protocol_status,
                        protocol.protocol_type,
                        protocol.approval_date,
                        ROW_NUMBER() OVER (
                            PARTITION BY protocol.protocol_base
                            ORDER BY
                                COALESCE(
                                    protocol.sequence_number,
                                    -1
                                ) DESC,
                                protocol.protocol_id DESC
                        ) AS version_rank
                    FROM archive.irb_protocol_version protocol
                    INNER JOIN matching_families matching
                        ON matching.protocol_base =
                           protocol.protocol_base
                )
                SELECT
                    NULL::bigint AS record_id,
                    protocol_id,
                    protocol_base,
                    protocol_number,
                    title,
                    protocol_status,
                    protocol_type,
                    approval_date
                FROM ranked_versions
                WHERE version_rank = 1
                ORDER BY
                    approval_date DESC NULLS LAST,
                    protocol_base DESC
                """)
                .param("email", normalizedEmail)
                .query((resultSet, rowNumber) ->
                        mapStudy(resultSet)
                )
                .list();

        return new InvestigatorProfileResponse(
                identity.name(),
                identity.email(),
                identity.buid(),
                currentStudies.size(),
                historicalStudies.size(),
                currentStudies,
                historicalStudies
        );
    }

    private static InvestigatorStudyResponse mapStudy(
            java.sql.ResultSet resultSet
    ) throws java.sql.SQLException {
        return new InvestigatorStudyResponse(
                resultSet.getObject("record_id", Long.class),
                resultSet.getObject("protocol_id", Long.class),
                resultSet.getString("protocol_base"),
                resultSet.getString("protocol_number"),
                resultSet.getString("title"),
                resultSet.getString("protocol_status"),
                resultSet.getString("protocol_type"),
                resultSet.getObject(
                        "approval_date",
                        LocalDate.class
                )
        );
    }

    private record InvestigatorIdentity(
            String name,
            String email,
            String buid
    ) {
    }
}
