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
                SELECT
                    (SELECT COUNT(*)
                     FROM archive.irb_protocol) AS irb,
                    (SELECT COUNT(DISTINCT protocol_base)
                     FROM archive.irb_protocol_version)
                        AS protocol_families,
                    (SELECT COUNT(*)
                     FROM archive.irb_protocol_version)
                        AS protocol_versions,
                    (SELECT COUNT(*)
                     FROM archive.irb_submission) AS submissions,
                    (SELECT COUNT(*)
                     FROM archive.irb_funding_source)
                        AS funding_records,
                    (SELECT COUNT(*)
                     FROM archive.irb_timeline_event)
                        AS timeline_events,
                    (SELECT COUNT(DISTINCT award_number)
                     FROM archive.award_version) AS awards,
                    (SELECT COUNT(*)
                     FROM archive.award_version)
                        AS award_history_records,
                    (SELECT COUNT(DISTINCT proposal_number)
                     FROM archive.proposal_version) AS proposals,
                    (SELECT COUNT(*)
                     FROM archive.proposal_version)
                        AS proposal_history_records,
                    (SELECT COUNT(*)
                     FROM archive.negotiation) AS negotiations,
                    (SELECT COUNT(DISTINCT subaward_code)
                     FROM archive.subaward) AS subawards,
                    0 AS documents
                """)
                .query(DashboardDto.class)
                .single();
    }
}
