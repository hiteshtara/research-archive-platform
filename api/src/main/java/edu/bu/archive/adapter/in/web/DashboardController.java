package edu.bu.archive.adapter.in.web;

import edu.bu.archive.dto.DashboardDto;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/dashboard")
@Transactional(readOnly = true)
public class DashboardController {

    private final JdbcClient jdbcClient;

    public DashboardController(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    @GetMapping
    public DashboardDto dashboard() {
        return jdbcClient.sql("""
                WITH protocol_counts AS (
                    SELECT
                        COUNT(DISTINCT protocol_number)
                            AS protocol_families,
                        COUNT(*) AS protocol_versions
                    FROM archive.protocol_version
                ),
                award_counts AS (
                    SELECT
                        COUNT(DISTINCT award_number) AS awards,
                        COUNT(*) AS award_history_records
                    FROM archive.award_version
                ),
                proposal_counts AS (
                    SELECT
                        COUNT(DISTINCT proposal_number) AS proposals,
                        COUNT(*) AS proposal_history_records
                    FROM archive.proposal_version
                )
                SELECT
                    (SELECT COUNT(*)
                     FROM archive.irb_protocol) AS irb,
                    protocol_counts.protocol_families,
                    protocol_counts.protocol_versions,
                    (SELECT COUNT(*)
                     FROM archive.irb_submission) AS submissions,
                    (SELECT COUNT(*)
                     FROM archive.irb_funding_source)
                        AS funding_records,
                    (SELECT COUNT(*)
                     FROM archive.irb_timeline_event)
                        AS timeline_events,
                    award_counts.awards,
                    award_counts.award_history_records,
                    proposal_counts.proposals,
                    proposal_counts.proposal_history_records,
                    (SELECT COUNT(*)
                     FROM archive.negotiation) AS negotiations,
                    (SELECT COUNT(DISTINCT subaward_code)
                     FROM archive.subaward) AS subawards,
                    0 AS documents
                FROM protocol_counts
                CROSS JOIN award_counts
                CROSS JOIN proposal_counts
                """)
                .query(DashboardDto.class)
                .single();
    }
}
