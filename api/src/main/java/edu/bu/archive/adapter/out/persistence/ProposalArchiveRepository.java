package edu.bu.archive.adapter.out.persistence;

import edu.bu.archive.adapter.in.web.dto.proposal.ProposalAwardResponse;
import edu.bu.archive.adapter.in.web.dto.proposal.ProposalFamilySummaryResponse;
import edu.bu.archive.adapter.in.web.dto.proposal.ProposalRowResponse;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public class ProposalArchiveRepository {

    private final JdbcClient jdbc;

    public ProposalArchiveRepository(
            JdbcClient jdbc
    ) {
        this.jdbc = jdbc;
    }

    public List<ProposalFamilySummaryResponse> findFamilies(
            String query,
            int limit
    ) {
        String normalizedQuery =
                query == null
                        ? ""
                        : query.trim();
        String filter = normalizedQuery.isEmpty()
                ? ""
                : """
                  AND (
                        proposal_number ILIKE '%' || :query || '%'
                        OR title ILIKE '%' || :query || '%'
                        OR sponsor_name ILIKE '%' || :query || '%'
                        OR lead_unit_name ILIKE '%' || :query || '%'
                        OR principal_investigator_name
                            ILIKE '%' || :query || '%'
                  )
                """;

        JdbcClient.StatementSpec statement = jdbc.sql("""
                WITH ranked AS (
                    SELECT
                        proposal_id,
                        proposal_number,
                        version_number,
                        title,
                        proposal_sequence_status,
                        sponsor_name,
                        lead_unit_name,
                        principal_investigator_name,
                        ROW_NUMBER() OVER (
                            PARTITION BY proposal_number
                            ORDER BY
                                version_number DESC,
                                source_update_timestamp DESC NULLS LAST,
                                proposal_id DESC
                        ) AS row_rank
                    FROM archive.proposal_version
                )
                SELECT
                    proposal_number,
                    title,
                    proposal_sequence_status AS status,
                    sponsor_name,
                    lead_unit_name,
                    principal_investigator_name
                        AS principal_investigator,
                    version_number AS latest_version_number,
                    proposal_id AS current_proposal_id
                FROM ranked
                WHERE row_rank = 1
                """ + filter + """
                ORDER BY proposal_number
                LIMIT :limit
                """);
        if (!normalizedQuery.isEmpty()) {
            statement = statement.param("query", normalizedQuery);
        }
        return statement
                .param("limit", limit)
                .query(ProposalFamilySummaryResponse.class)
                .list();
    }

    public Optional<ProposalRowResponse> findCurrent(
            String proposalNumber
    ) {
        return jdbc.sql("""
                SELECT
                    proposal_id,
                    proposal_number,
                    version_number,
                    title,
                    proposal_sequence_status AS status,
                    proposal_type,
                    activity_type,
                    sponsor_code,
                    sponsor_name,
                    lead_unit_number,
                    lead_unit_name,
                    principal_investigator_id,
                    principal_investigator_name
                        AS principal_investigator,
                    initial_start_date,
                    initial_end_date,
                    initial_direct_cost,
                    initial_indirect_cost,
                    initial_total_cost,
                    total_start_date,
                    total_end_date,
                    total_direct_cost,
                    total_indirect_cost,
                    total_cost
                FROM archive.proposal_version
                WHERE proposal_number = :proposalNumber
                ORDER BY
                    version_number DESC,
                    source_update_timestamp DESC NULLS LAST,
                    proposal_id DESC
                LIMIT 1
                """)
                .param("proposalNumber", proposalNumber)
                .query(ProposalRowResponse.class)
                .optional();
    }

    public long countVersions(
            String proposalNumber
    ) {
        Long count = jdbc.sql("""
                SELECT COUNT(*)
                FROM archive.proposal_version
                WHERE proposal_number = :proposalNumber
                """)
                .param("proposalNumber", proposalNumber)
                .query(Long.class)
                .single();

        return count == null ? 0L : count;
    }

    public List<ProposalRowResponse> findVersionRows(
            String proposalNumber,
            int limit,
            int offset
    ) {
        return jdbc.sql("""
                SELECT
                    proposal_id,
                    proposal_number,
                    version_number,
                    title,
                    proposal_sequence_status AS status,
                    proposal_type,
                    activity_type,
                    sponsor_code,
                    sponsor_name,
                    lead_unit_number,
                    lead_unit_name,
                    principal_investigator_id,
                    principal_investigator_name
                        AS principal_investigator,
                    initial_start_date,
                    initial_end_date,
                    initial_direct_cost,
                    initial_indirect_cost,
                    initial_total_cost,
                    total_start_date,
                    total_end_date,
                    total_direct_cost,
                    total_indirect_cost,
                    total_cost
                FROM archive.proposal_version
                WHERE proposal_number = :proposalNumber
                ORDER BY
                    version_number DESC,
                    source_update_timestamp DESC NULLS LAST,
                    proposal_id DESC
                LIMIT :limit
                OFFSET :offset
                """)
                .param("proposalNumber", proposalNumber)
                .param("limit", limit)
                .param("offset", offset)
                .query(ProposalRowResponse.class)
                .list();
    }

    /*
     * row_rank's tiebreaker is award_funding_proposal_id DESC (added
     * alongside V075, which dropped uq_proposal_award and allowed
     * genuine natural-key duplicates - e.g. Proposal family 2975/
     * award_id 462515 - to coexist as real, distinct
     * archive.proposal_award rows). Without it, two rows sharing the
     * same (award_id, proposal_id) tie on the existing ORDER BY and
     * which one row_rank = 1 picks becomes plan-dependent, not stable
     * across query re-execution. This does not change which award_id
     * values are shown or this method's existing "collapse to one row
     * per award_id" behavior - it only makes an already-existing tie
     * deterministic.
     */
    public List<ProposalAwardResponse> findAwards(
            String proposalNumber
    ) {
        return jdbc.sql("""
                WITH ranked_awards AS (
                    SELECT
                        relationship.proposal_id,
                        relationship.award_id,
                        relationship.award_number,
                        ROW_NUMBER() OVER (
                            PARTITION BY relationship.award_id
                            ORDER BY
                                relationship.proposal_id DESC,
                                relationship.award_funding_proposal_id DESC
                        ) AS row_rank
                    FROM archive.proposal_award relationship
                    INNER JOIN archive.proposal_version proposal
                        ON proposal.proposal_id = relationship.proposal_id
                    WHERE proposal.proposal_number = :proposalNumber
                )
                SELECT
                    proposal_id,
                    award_id,
                    award_number
                FROM ranked_awards
                WHERE row_rank = 1
                ORDER BY
                    award_number NULLS LAST,
                    award_id NULLS LAST,
                    proposal_id
                """)
                .param("proposalNumber", proposalNumber)
                .query(ProposalAwardResponse.class)
                .list();
    }
}
