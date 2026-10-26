package edu.bu.archive.application.service;

import edu.bu.archive.adapter.in.web.dto.DashboardDto;
import edu.bu.archive.application.authorization.AuthorizationProperties;
import edu.bu.archive.application.authorization.AwardRecordAccess;
import edu.bu.archive.application.authorization.SqlFragment;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/**
 * Dashboard counts.
 *
 * <p>Moved out of the controller, which held a {@link JdbcClient} and ran
 * this query with no authorization at all.
 *
 * <p>DECISIONS 2, 3 and 4 (2026-10-26): the counts are scoped to what the
 * caller may see. A Central user's scope renders no predicate, so they still
 * get archive-wide totals; a department user's renders a lead-unit predicate
 * with descendants where the grant allows, so their totals describe their own
 * records. With enforcement off the predicate is empty and the statement is
 * the one that ran before.
 *
 * <p>Only the Award-derived figures are scoped here - the Award counts and
 * the AWARD branch of the document tally. IRB, Proposal, Negotiation and
 * Subaward counts are NOT yet scoped, because no approved record rule exists
 * for those modules. That is a known, deliberate gap, not an oversight: see
 * the PR for the list.
 */
@Service
public class DashboardService {

    private final JdbcClient jdbcClient;
    private final AwardRecordAccess recordAccess;

    public DashboardService(
            JdbcClient jdbcClient,
            ObjectProvider<AwardRecordAccess> recordAccessProvider,
            ObjectProvider<AuthorizationProperties> authorizationProperties
    ) {
        this.jdbcClient = jdbcClient;
        AwardRecordAccess access =
                recordAccessProvider == null ? null : recordAccessProvider.getIfAvailable();
        this.recordAccess = access != null ? access : AwardRecordAccess.PERMIT_ALL;
        AwardRecordAccess.requireEnforceableWiring(this.recordAccess, authorizationProperties);
    }

    public DashboardDto counts() {
        // DECISION 1: no provisioned access means no research data, and an
        // explicit problem rather than a dashboard of zeroes.
        recordAccess.requireProvisioned();

        SqlFragment scope = recordAccess.awardScopeSql("av");

        JdbcClient.StatementSpec statement = jdbcClient.sql("""
                WITH award_counts AS (
                    SELECT
                        COUNT(DISTINCT award_number) AS awards,
                        COUNT(*) AS award_history_records
                    FROM archive.award_version av
                    WHERE TRUE""" + scope.sql() + """
                ),
                proposal_counts AS (
                    SELECT
                        COUNT(DISTINCT proposal_number) AS proposals,
                        COUNT(*) AS proposal_history_records
                    FROM archive.proposal_version
                ),
                kuali_documents AS (
                    SELECT 'AWARD' AS module, workflow_document_number AS document_number
                    FROM archive.award_version av
                    WHERE workflow_document_number IS NOT NULL""" + scope.sql() + """

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
                """);
        for (var parameter : scope.params().entrySet()) {
            statement = statement.param(parameter.getKey(), parameter.getValue());
        }
        return statement.query(DashboardDto.class).single();
    }
}
