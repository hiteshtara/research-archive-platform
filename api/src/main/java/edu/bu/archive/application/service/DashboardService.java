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
 * <p>Proposal joined them on 2026-10-27, closing D2. All THREE
 * Proposal-derived figures are scoped together - the family count, the
 * version-row count, and the PROPOSAL branch of the document tally -
 * because scoping a subset is worse than scoping none: it produces a
 * dashboard whose own numbers disagree with each other and with the
 * Proposal search the same user can run.
 *
 * <p>IRB is EXCLUDED rather than scoped (decision D9, 2026-10-27). It has
 * no approved record rule and will not get one, so there is nothing to
 * apply - but leaving archive-wide IRB totals on an otherwise authorized
 * dashboard would contradict the shared-search decision, which already
 * removes IRB entirely once enforcement is on. So when enforcement is on
 * the four IRB figures are omitted from the response and the IRB branch
 * leaves the document union, for EVERY caller including Central.
 *
 * <p>This is isolation of a shared surface, not an authorization rule: the
 * IRB module's own endpoints, tables and migrations are untouched. With
 * enforcement off - today's deployed configuration - the dashboard is
 * exactly what it always was.
 */
@Service
public class DashboardService {

    private final JdbcClient jdbcClient;
    private final AwardRecordAccess recordAccess;
    private final edu.bu.archive.application.authorization.SubawardRecordAccess subawardAccess;
    private final edu.bu.archive.application.authorization.NegotiationRecordAccess negotiationAccess;
    private final edu.bu.archive.application.authorization.ProposalRecordAccess proposalAccess;
    private final ObjectProvider<AuthorizationProperties> authorizationProperties;

    public DashboardService(
            JdbcClient jdbcClient,
            ObjectProvider<AwardRecordAccess> recordAccessProvider,
            ObjectProvider<AuthorizationProperties> authorizationProperties
    ) {
        this(jdbcClient, recordAccessProvider, null, null, null, authorizationProperties);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public DashboardService(
            JdbcClient jdbcClient,
            ObjectProvider<AwardRecordAccess> recordAccessProvider,
            ObjectProvider<edu.bu.archive.application.authorization.SubawardRecordAccess>
                    subawardAccessProvider,
            ObjectProvider<edu.bu.archive.application.authorization.NegotiationRecordAccess>
                    negotiationAccessProvider,
            ObjectProvider<edu.bu.archive.application.authorization.ProposalRecordAccess>
                    proposalAccessProvider,
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

        edu.bu.archive.application.authorization.ProposalRecordAccess proposal =
                proposalAccessProvider == null ? null : proposalAccessProvider.getIfAvailable();
        this.proposalAccess = proposal != null
                ? proposal
                : edu.bu.archive.application.authorization.ProposalRecordAccess.PERMIT_ALL;
        edu.bu.archive.application.authorization.ProposalRecordAccess
                .requireEnforceableWiring(this.proposalAccess, authorizationProperties);

        this.authorizationProperties = authorizationProperties;
    }

    /*
     * Deliberately reads the enforcement flag rather than
     * recordAccess.unrestricted(). D9 excludes IRB for EVERY caller once
     * enforcement is on - a Central user is not an exception, exactly as in
     * GlobalSearchService.irbExcludedFromSharedSearch(). Using unrestricted()
     * would quietly exempt Central and leave the two surfaces disagreeing.
     */
    private boolean irbExcludedFromDashboard() {
        if (authorizationProperties == null) {
            return false;
        }
        AuthorizationProperties settings = authorizationProperties.getIfAvailable();
        return settings != null && settings.isEnforcementEnabled();
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
        SqlFragment proposalScope =
                proposalAccess.proposalScopeSql("pv").withParameterPrefix("prop_");

        /*
         * D9. Two separate removals, because IRB reaches the response twice:
         * as its own four figures, and as a contributing branch of the
         * single documents tally. Removing only the first would leave the
         * document count carrying an IRB subtotal that no caller could see
         * or reconcile.
         */
        boolean excludeIrb = irbExcludedFromDashboard();
        String irbDocumentBranch = excludeIrb ? "" : """

                    UNION ALL

                    SELECT 'IRB', document_number
                    FROM archive.irb_protocol_version
                    WHERE document_number IS NOT NULL""";
        /*
         * NULL, not 0. A zero would be read as "the archive holds no IRB
         * protocols", which is false; the DTO is NON_NULL so these columns
         * leave the JSON altogether.
         */
        // Leading newline: a text block strips the one after its opening
        // delimiter, so without this the concatenation yields "SELECTCAST(".
        String irbFigures = "\n" + (excludeIrb
                ? """
                    CAST(NULL AS BIGINT) AS irb,
                    CAST(NULL AS BIGINT) AS submissions,
                    CAST(NULL AS BIGINT) AS funding_records,
                    CAST(NULL AS BIGINT) AS timeline_events,"""
                : """
                    (SELECT COUNT(*) FROM archive.irb_protocol) AS irb,
                    (SELECT COUNT(*) FROM archive.irb_submission) AS submissions,
                    (SELECT COUNT(*) FROM archive.irb_funding_source) AS funding_records,
                    (SELECT COUNT(*) FROM archive.irb_timeline_event) AS timeline_events,""");

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
                        COUNT(DISTINCT pv.proposal_number) AS proposals,
                        COUNT(*) AS proposal_history_records
                    FROM archive.proposal_version pv
                    WHERE TRUE""" + proposalScope.sql() + """
                ),
                kuali_documents AS (
                    SELECT 'AWARD' AS module, workflow_document_number AS document_number
                    FROM archive.award_version av
                    WHERE workflow_document_number IS NOT NULL""" + scope.sql() + """

                    UNION ALL

                    SELECT 'PROPOSAL', pv.document_number
                    FROM archive.proposal_version pv
                    WHERE pv.document_number IS NOT NULL""" + proposalScope.sql() + """

                    UNION ALL

                    SELECT 'NEGOTIATION', neg.document_number
                    FROM archive.negotiation neg
                    WHERE neg.document_number IS NOT NULL""" + negotiationScope.sql() + """

                    UNION ALL

                    SELECT 'SUBAWARD', sub.document_number
                    FROM archive.subaward sub
                    WHERE sub.document_number IS NOT NULL""" + subawardScope.sql() + irbDocumentBranch + """
                )
                SELECT""" + irbFigures + """
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
        for (var parameter : proposalScope.params().entrySet()) {
            statement = statement.param(parameter.getKey(), parameter.getValue());
        }
        return statement.query(DashboardDto.class).single();
    }
}
