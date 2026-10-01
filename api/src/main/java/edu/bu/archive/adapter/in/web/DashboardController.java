package edu.bu.archive.adapter.in.web;

import edu.bu.archive.adapter.in.web.dto.DashboardDto;

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
    private final edu.bu.archive.application.authorization.RecordAuthorizationService authorization;

    public DashboardController(JdbcClient jdbcClient) {
        this(jdbcClient, (edu.bu.archive.application.authorization.RecordAuthorizationService) null);
    }

    public DashboardController(
            JdbcClient jdbcClient,
            edu.bu.archive.application.authorization.RecordAuthorizationService authorization
    ) {
        this.jdbcClient = jdbcClient;
        this.authorization = authorization;
    }

    @org.springframework.beans.factory.annotation.Autowired
    public DashboardController(
            JdbcClient jdbcClient,
            org.springframework.beans.factory.ObjectProvider<
                    edu.bu.archive.application.authorization.RecordAuthorizationService> authorization
    ) {
        this(jdbcClient, authorization.getIfAvailable());
    }

    @GetMapping
    public DashboardDto dashboard() {
        if (authorization != null && !authorization.unrestricted()) {
            return scopedDashboard();
        }
        return jdbcClient.sql("""
                WITH award_counts AS (
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
                ),
                kuali_documents AS (
                    SELECT 'AWARD' AS module, workflow_document_number AS document_number
                    FROM archive.award_version
                    WHERE workflow_document_number IS NOT NULL

                    UNION ALL

                    SELECT 'PROPOSAL', document_number
                    FROM archive.proposal_version
                    WHERE document_number IS NOT NULL

                    UNION ALL

                    SELECT 'NEGOTIATION', document_number
                    FROM archive.negotiation
                    WHERE document_number IS NOT NULL

                    UNION ALL

                    SELECT 'SUBAWARD', document_number
                    FROM archive.subaward
                    WHERE document_number IS NOT NULL

                    UNION ALL

                    SELECT 'IRB', document_number
                    FROM archive.irb_protocol_version
                    WHERE document_number IS NOT NULL
                )
                SELECT
                    (SELECT COUNT(*)
                     FROM archive.irb_protocol) AS irb,
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
                    (SELECT COUNT(DISTINCT (module, document_number))
                     FROM kuali_documents) AS documents
                FROM award_counts
                CROSS JOIN proposal_counts
                """)
                .query(DashboardDto.class)
                .single();
    }

    /*
     * Record authorization: counts for a restricted caller come from the SAME
     * scope predicates as search - never archive-wide numbers. Modules with
     * no non-Central scoping yet (IRB, Negotiation, Subaward, documents)
     * report 0 rather than an archive-wide total.
     */
    private DashboardDto scopedDashboard() {
        var award = authorization.scopeSql(edu.bu.archive.application.authorization.RecordModule.AWARD, "av");
        var proposal = authorization.scopeSql(edu.bu.archive.application.authorization.RecordModule.PROPOSAL, "ranked");
        var awardSpec = jdbcClient.sql("""
                SELECT COUNT(DISTINCT av.award_number) AS families, COUNT(*) AS versions
                FROM archive.award_version av WHERE TRUE""" + award.sql());
        if (!award.params().isEmpty()) {
            awardSpec = awardSpec.params(award.params());
        }
        var awards = awardSpec.query((rs, i) -> new long[] {rs.getLong("families"), rs.getLong("versions")}).single();
        var proposalSpec = jdbcClient.sql("""
                WITH ranked AS (
                    SELECT proposal_id, proposal_number, lead_unit_number,
                           ROW_NUMBER() OVER (PARTITION BY proposal_number ORDER BY version_number DESC,
                               source_update_timestamp DESC NULLS LAST, proposal_id DESC) AS row_rank
                    FROM archive.proposal_version
                )
                SELECT COUNT(*) FILTER (WHERE row_rank = 1) AS families, COUNT(*) AS versions
                FROM ranked WHERE TRUE""" + proposal.sql());
        if (!proposal.params().isEmpty()) {
            proposalSpec = proposalSpec.params(proposal.params());
        }
        var proposals = proposalSpec.query((rs, i) -> new long[] {rs.getLong("families"), rs.getLong("versions")}).single();
        return new DashboardDto(0, 0, 0, 0, awards[0], awards[1], proposals[0], proposals[1], 0, 0, 0);
    }
}
