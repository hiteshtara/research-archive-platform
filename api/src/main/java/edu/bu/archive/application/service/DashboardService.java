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
 * <p>Scoped here: the Award counts and the AWARD branch of the document
 * tally, and - since the funding-inheritance rule was approved (D6) - the
 * Subaward count and the SUBAWARD branch.
 *
 * <p>Negotiation joined them on 2026-10-27 with the approved hybrid rule:
 * the Negotiation count and the NEGOTIATION branch are now scoped too.
 *
 * <p>NOT scoped: IRB and Proposal. IRB has no approved record rule at all.
 * Proposal HAS one and its endpoints are scoped, but these two figures were
 * not changed when it landed - a real remaining gap, recorded in
 * AUTHORIZATION_DECISIONS.md under D2 rather than left to be discovered.
 * The dashboard as a whole is not yet authorized.
 */
@Service
public class DashboardService {

    private final JdbcClient jdbcClient;
    private final AwardRecordAccess recordAccess;
    private final edu.bu.archive.application.authorization.SubawardRecordAccess subawardAccess;
    private final edu.bu.archive.application.authorization.NegotiationRecordAccess negotiationAccess;

    public DashboardService(
            JdbcClient jdbcClient,
            ObjectProvider<AwardRecordAccess> recordAccessProvider,
            ObjectProvider<AuthorizationProperties> authorizationProperties
    ) {
        this(jdbcClient, recordAccessProvider, null, null, authorizationProperties);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public DashboardService(
            JdbcClient jdbcClient,
            ObjectProvider<AwardRecordAccess> recordAccessProvider,
            ObjectProvider<edu.bu.archive.application.authorization.SubawardRecordAccess>
                    subawardAccessProvider,
            ObjectProvider<edu.bu.archive.application.authorization.NegotiationRecordAccess>
                    negotiationAccessProvider,
            ObjectProvider<AuthorizationProperties> authorizationProperties
    ) {
        this.jdbcClient = jdbcClient;
        AwardRecordAccess access =
                recordAccessProvider == null ? null : recordAccessProvider.getIfAvailable();
        this.recordAccess = access != null ? access : AwardRecordAccess.PERMIT_ALL;
        AwardRecordAccess.requireEnforceableWiring(this.recordAccess, authorizationProperties);

        edu.bu.archive.application.authorization.SubawardRecordAccess subaward =
                subawardAccessProvider == null ? null : subawardAccessProvider.getIfAvailable();
        this.subawardAccess = subaward != null
                ? subaward
                : edu.bu.archive.application.authorization.SubawardRecordAccess.PERMIT_ALL;
        edu.bu.archive.application.authorization.SubawardRecordAccess
                .requireEnforceableWiring(this.subawardAccess, authorizationProperties);

        edu.bu.archive.application.authorization.NegotiationRecordAccess negotiation =
                negotiationAccessProvider == null ? null : negotiationAccessProvider.getIfAvailable();
        this.negotiationAccess = negotiation != null
                ? negotiation
                : edu.bu.archive.application.authorization.NegotiationRecordAccess.PERMIT_ALL;
        edu.bu.archive.application.authorization.NegotiationRecordAccess
                .requireEnforceableWiring(this.negotiationAccess, authorizationProperties);
    }

    public DashboardDto counts() {
        // DECISION 1: no provisioned access means no research data, and an
        // explicit problem rather than a dashboard of zeroes.
        recordAccess.requireProvisioned();

        SqlFragment scope = recordAccess.awardScopeSql("av");
        /*
         * Prefixed, because the Subaward predicate is the Award predicate
         * applied to a joined Award row and so binds the same parameter
         * names. Two fragments in one statement must not fight over them -
         * today they would carry equal values, but relying on that is how a
         * later change to either one silently corrupts the other.
         */
        SqlFragment subawardScope =
                subawardAccess.subawardScopeSql("sub").withParameterPrefix("sub_");
        SqlFragment negotiationScope =
                negotiationAccess.negotiationScopeSql("neg").withParameterPrefix("neg_");

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

                    SELECT 'NEGOTIATION', neg.document_number
                    FROM archive.negotiation neg
                    WHERE neg.document_number IS NOT NULL""" + negotiationScope.sql() + """

                    UNION ALL

                    SELECT 'SUBAWARD', sub.document_number
                    FROM archive.subaward sub
                    WHERE sub.document_number IS NOT NULL""" + subawardScope.sql() + """

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
                     FROM archive.negotiation neg
                     WHERE TRUE""" + negotiationScope.sql() + """
                    ) AS negotiations,
                    (SELECT COUNT(DISTINCT sub.subaward_code)
                     FROM archive.subaward sub
                     WHERE TRUE""" + subawardScope.sql() + """
                    ) AS subawards,
                    (SELECT COUNT(DISTINCT (module, document_number))
                     FROM kuali_documents) AS documents
                FROM award_counts
                CROSS JOIN proposal_counts
                """);
        for (var parameter : scope.params().entrySet()) {
            statement = statement.param(parameter.getKey(), parameter.getValue());
        }
        for (var parameter : subawardScope.params().entrySet()) {
            statement = statement.param(parameter.getKey(), parameter.getValue());
        }
        for (var parameter : negotiationScope.params().entrySet()) {
            statement = statement.param(parameter.getKey(), parameter.getValue());
        }
        return statement.query(DashboardDto.class).single();
    }
}
