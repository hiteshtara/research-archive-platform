package edu.bu.archive.adapter.out.persistence;

import edu.bu.archive.adapter.in.web.dto.proposal.ProposalAwardResponse;
import edu.bu.archive.adapter.in.web.dto.proposal.ProposalFamilySummaryResponse;
import edu.bu.archive.adapter.in.web.dto.proposal.ProposalRowResponse;
import edu.bu.archive.application.proposal.ProposalSearchFilters;

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

    /*
     * Paged, filterable family search for the Proposals search page.
     * Same latest-version ranking and free-text columns as findFamilies
     * above (which Global Search keeps using unchanged); structured
     * filters are ANDed on the latest version, and the count shares the
     * exact same WHERE so totalElements always matches the pages.
     */
    private static final String FAMILY_PAGE_RANKED = """
            WITH ranked AS (
                SELECT
                    proposal_id,
                    proposal_number,
                    version_number,
                    title,
                    proposal_sequence_status,
                    sponsor_code,
                    sponsor_name,
                    lead_unit_number,
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
            """;

    /*
     * Structured PI filter (Req 12): matches the latest version's people
     * whose role is PI or MPI - MPI is what BU's Kuali labels "Co-PI". COI
     * and KP never match. Free text (the :query block) is unchanged and
     * still reads only principal_investigator_name.
     *
     * The person join is by proposal_id ONLY, because that is Kuali's own
     * relationship (InstitutionalProposal.projectPersons inverse-foreignkey
     * proposalId). Some PROPOSAL_PERSONS rows carry an earlier
     * SEQUENCE_NUMBER than their version (145 at staging, 2026-09-30); a
     * sequence join would silently drop them. Qualify ranked.proposal_id -
     * a bare proposal_id would bind to ppf and make the EXISTS always true.
     *
     * The principal_investigator_name branch is a TEMPORARY fallback that
     * preserves every match the filter made before this change. 37 PI person
     * rows were missing from dev at 2026-09-30 (data-quality issue DQ-2); how
     * many of those versions have a stored PI name is unknown, so this does
     * NOT claim complete PI coverage. It is an OR: a stored-name match is
     * never suppressed by a same-name person row with another role, and a
     * COI/KP row alone never qualifies. It is PI-only and can never find an
     * MPI. Remove it once DQ-2 is fixed and person rows reconcile.
     */
    private static final String FAMILY_PAGE_WHERE = """
            WHERE row_rank = 1
              AND (CAST(:query AS TEXT) IS NULL OR (
                    proposal_number ILIKE '%' || :query || '%'
                    OR title ILIKE '%' || :query || '%'
                    OR sponsor_name ILIKE '%' || :query || '%'
                    OR lead_unit_name ILIKE '%' || :query || '%'
                    OR principal_investigator_name
                        ILIKE '%' || :query || '%'
              ))
              AND (CAST(:sponsor AS TEXT) IS NULL
                   OR sponsor_name ILIKE '%' || :sponsor || '%'
                   OR sponsor_code ILIKE '%' || :sponsor || '%')
              AND (CAST(:leadUnit AS TEXT) IS NULL
                   OR lead_unit_name ILIKE '%' || :leadUnit || '%'
                   OR lead_unit_number ILIKE '%' || :leadUnit || '%')
              AND (CAST(:principalInvestigator AS TEXT) IS NULL
                   OR EXISTS (
                       SELECT 1 FROM archive.proposal_person ppf
                       WHERE ppf.proposal_id = ranked.proposal_id
                         AND UPPER(TRIM(ppf.contact_role_code)) IN ('PI', 'MPI')
                         AND ppf.full_name ILIKE '%' || :principalInvestigator || '%'
                   )
                   OR principal_investigator_name
                      ILIKE '%' || :principalInvestigator || '%')
            """;

    private static JdbcClient.StatementSpec bindFamilyPage(
            JdbcClient.StatementSpec statement,
            String query,
            ProposalSearchFilters filters
    ) {
        String normalizedQuery =
                query == null || query.isBlank() ? null : query.trim();
        ProposalSearchFilters f =
                filters == null ? ProposalSearchFilters.none() : filters;
        return statement
                .param("query", normalizedQuery)
                .param("sponsor", f.sponsor())
                .param("leadUnit", f.leadUnit())
                .param("principalInvestigator", f.principalInvestigator());
    }

    public List<ProposalFamilySummaryResponse> findFamilyPage(
            String query,
            ProposalSearchFilters filters,
            int limit,
            int offset
    ) {
        return bindFamilyPage(jdbc.sql(FAMILY_PAGE_RANKED + """
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
                """ + FAMILY_PAGE_WHERE + """
                ORDER BY proposal_number
                LIMIT :limit OFFSET :offset
                """), query, filters)
                .param("limit", limit)
                .param("offset", offset)
                .query(ProposalFamilySummaryResponse.class)
                .list();
    }

    public long countFamilyPage(String query, ProposalSearchFilters filters) {
        Long count = bindFamilyPage(jdbc.sql(FAMILY_PAGE_RANKED + """
                SELECT COUNT(*)
                FROM ranked
                """ + FAMILY_PAGE_WHERE), query, filters)
                .query(Long.class)
                .single();
        return count == null ? 0L : count;
    }

    /*
     * Set-based batch lookup for Global Search's semantic-result card
     * enrichment (see GlobalSearchService) - one query resolving every
     * distinct proposal_number a batch of semantic hits references,
     * never one query per result. Same "latest version wins" ranking as
     * findFamilies above, kept as its own query rather than reusing
     * findFamilies directly since that method's ILIKE-based filter
     * shape doesn't fit an exact-set IN lookup. Proposals with no
     * matching row (stale/removed since the embedding was built) are
     * simply absent from the result list - the caller must treat that
     * as "no enrichment available", not an error.
     */
    public List<ProposalSemanticSummaryRow> findCurrentSummariesForNumbers(
            List<String> proposalNumbers
    ) {
        if (proposalNumbers.isEmpty()) {
            return List.of();
        }

        return jdbc.sql("""
                WITH ranked AS (
                    SELECT
                        proposal_number,
                        version_number,
                        title,
                        proposal_sequence_status,
                        sponsor_name,
                        principal_investigator_name,
                        ROW_NUMBER() OVER (
                            PARTITION BY proposal_number
                            ORDER BY
                                version_number DESC,
                                source_update_timestamp DESC NULLS LAST,
                                proposal_id DESC
                        ) AS row_rank
                    FROM archive.proposal_version
                    WHERE proposal_number IN (:proposalNumbers)
                )
                SELECT
                    proposal_number,
                    title,
                    proposal_sequence_status AS status,
                    sponsor_name AS sponsor,
                    principal_investigator_name
                        AS principal_investigator
                FROM ranked
                WHERE row_rank = 1
                """)
                .param("proposalNumbers", proposalNumbers)
                .query(ProposalSemanticSummaryRow.class)
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
