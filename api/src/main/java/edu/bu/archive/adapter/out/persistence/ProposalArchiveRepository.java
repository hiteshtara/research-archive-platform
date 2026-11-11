package edu.bu.archive.adapter.out.persistence;

import edu.bu.archive.application.authorization.SqlFragment;
import edu.bu.archive.adapter.in.web.dto.proposal.ProposalAwardResponse;
import edu.bu.archive.adapter.in.web.dto.proposal.ProposalFamilySummaryResponse;
import edu.bu.archive.adapter.in.web.dto.proposal.ProposalRowResponse;
import edu.bu.archive.application.proposal.ProposalSearchFilters;
import edu.bu.archive.application.search.LexicalMatch;
import edu.bu.archive.application.search.MatchExplanation;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.Collection;
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

    /*
     * The Proposal free-text predicate, in ONE place.
     *
     * It is used by Global Search's Proposal leg (findFamilies) and by the
     * Proposal module's paged search and its count (findFamilyPage /
     * countFamilyPage). Those were three separate copies of the same OR
     * chain. QA TC-040's word-start fix landing in two of them and not the
     * third would reproduce the very defect it fixes - a page and its own
     * total describing different row sets - so there is now one definition
     * and the drift is impossible rather than merely unlikely.
     *
     * IDENTIFIER vs TEXT, the same split AwardArchiveRepository makes.
     * proposal_number keeps substring matching: pasting a fragment of a
     * proposal number is matching mid-string on purpose. The four text
     * fields are anchored to the START of a word when the term qualifies,
     * so "NSF" stops matching inside "TRANSFORMATION" and
     * "methyltransferase" - measured as 59 of the first 100 Proposal rows
     * for NSF on 2026-11-10 - while "cardio" still finds "cardiology",
     * because only the start of the word is anchored and not the whole word.
     *
     * :anchorWords is a bound constant, so the planner discards whichever
     * branch is false. The substring branch keeps '%' || :query || '%'
     * rather than moving to LexicalMatch.likePattern: this page does not
     * translate '*', AWARD_WILDCARD_HINT says so in as many words, and
     * quietly giving it wildcard semantics is a different change.
     *
     * REACHING THE RIGHT PROPOSAL. Anchoring alone would have made this
     * module worse, not better. Measured on 2026-11-10 against the running
     * revision: 3,746 Proposals are sponsored by the National Science
     * Foundation, and of the first 100 of them, 99 carry no word-start
     * "NSF" in any field this predicate reads - they matched "NSF" only
     * through coincidences like "TRANSFORMATION". Removing the coincidence
     * without adding a real path would leave them unreachable, which is the
     * TC-040 complaint reproduced rather than fixed.
     *
     * archive.sponsor.acronym is that path, and it is the SAME path the
     * Award leg already uses - not a mapping invented here. SPONSOR is
     * Kuali's shared sponsor master (see sql/extract/reference/17_sponsor.sql),
     * referenced by Award, Negotiation and Proposal alike, and
     * sql/extract/proposal/01_proposal_versions.sql joins it on exactly
     * this sponsor_code.
     *
     * Matched through an EXISTS so neither the page's FROM nor the count's
     * FROM changes: if those two diverged, totalElements and the page would
     * describe different row sets.
     *
     * See LexicalMatch for why the rule is word START and not whole word.
     */
    private static final String FAMILY_FREE_TEXT = """
                    proposal_number ILIKE '%' || :query || '%'
                    OR (:anchorWords = FALSE AND (
                            title ILIKE '%' || :query || '%'
                         OR sponsor_name ILIKE '%' || :query || '%'
                         OR lead_unit_name ILIKE '%' || :query || '%'
                         OR principal_investigator_name
                                ILIKE '%' || :query || '%'
                         OR EXISTS (
                                SELECT 1 FROM archive.sponsor sp2
                                WHERE sp2.sponsor_code = ranked.sponsor_code
                                  AND sp2.acronym ILIKE '%' || :query || '%'
                            )
                    ))
                    OR (:anchorWords = TRUE AND (
                            title ~* :wordRegex
                         OR sponsor_name ~* :wordRegex
                         OR lead_unit_name ~* :wordRegex
                         OR principal_investigator_name ~* :wordRegex
                         OR EXISTS (
                                SELECT 1 FROM archive.sponsor sp2
                                WHERE sp2.sponsor_code = ranked.sponsor_code
                                  AND sp2.acronym ~* :wordRegex
                            )
                    ))
            """;

    public List<ProposalFamilySummaryResponse> findFamilies(
            String query,
            int limit
    ) {
        return findFamilies(query, SqlFragment.NONE, limit);
    }

    /**
     * @param scope record-authorization predicate for the {@code ranked} CTE.
     *              This method also backs Global Search's Proposal leg, so
     *              scoping it covers that surface too.
     */
    public List<ProposalFamilySummaryResponse> findFamilies(
            String query,
            SqlFragment scope,
            int limit
    ) {
        SqlFragment familyScope = scope == null ? SqlFragment.NONE : scope;
        String normalizedQuery =
                query == null
                        ? ""
                        : query.trim();
        String filter = normalizedQuery.isEmpty()
                ? ""
                : "                  AND (\n" + FAMILY_FREE_TEXT + "                  )\n";

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
                        -- Carried for the sponsor-acronym EXISTS in
                        -- FAMILY_FREE_TEXT; not selected into the response.
                        sponsor_code,
                        -- Carried for the record-authorization predicate
                        -- below; not selected into the response.
                        lead_unit_number,
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
                """ + filter + familyScope.sql() + """
                ORDER BY proposal_number
                LIMIT :limit
                """);
        statement = bindScope(statement, familyScope);
        if (!normalizedQuery.isEmpty()) {
            statement = statement
                    .param("query", normalizedQuery)
                    .param("anchorWords", LexicalMatch.anchorsToWordStart(normalizedQuery))
                    .param("wordRegex", LexicalMatch.wordStartRegex(normalizedQuery));
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
     * MPI. Do NOT remove it automatically once DQ-2 is fixed: first prove
     * that the person-row branch alone returns every valid match the
     * fallback returns today, including name-format differences between
     * principal_investigator_name and proposal_person.full_name.
     */
    /**
     * Binds a record-authorization predicate's named parameters. Collections
     * bind as-is; Spring expands them for {@code IN (:name)}. A
     * {@link SqlFragment#NONE} binds nothing, so the statement is identical
     * to the unscoped one.
     */
    private static JdbcClient.StatementSpec bindScope(
            JdbcClient.StatementSpec statement,
            SqlFragment scope
    ) {
        JdbcClient.StatementSpec bound = statement;
        for (var parameter : scope.params().entrySet()) {
            bound = bound.param(parameter.getKey(), parameter.getValue());
        }
        return bound;
    }

    private static final String FAMILY_PAGE_WHERE = """
            WHERE row_rank = 1
              AND (CAST(:query AS TEXT) IS NULL OR (
            """ + FAMILY_FREE_TEXT + """
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
                /*
                 * Bound unconditionally, even for a null query: the
                 * CAST(:query AS TEXT) IS NULL guard short-circuits the OR
                 * chain, but the parameters still appear in the statement
                 * text and must resolve. wordStartRegex returns a
                 * deliberately unmatchable pattern for a term that does not
                 * qualify, so a null or non-anchored query fails closed.
                 */
                .param("anchorWords",
                        normalizedQuery != null
                                && LexicalMatch.anchorsToWordStart(normalizedQuery))
                .param("wordRegex", LexicalMatch.wordStartRegex(normalizedQuery))
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
        return findFamilyPage(query, filters, SqlFragment.NONE, limit, offset);
    }

    /**
     * @param scope record-authorization predicate for the {@code ranked} CTE,
     *              which exposes proposal_id, proposal_number and
     *              lead_unit_number - exactly what the scope builder needs.
     *              {@link SqlFragment#NONE} reproduces the unscoped statement.
     */
    public List<ProposalFamilySummaryResponse> findFamilyPage(
            String query,
            ProposalSearchFilters filters,
            SqlFragment scope,
            int limit,
            int offset
    ) {
        SqlFragment pageScope = scope == null ? SqlFragment.NONE : scope;
        return bindScope(bindFamilyPage(jdbc.sql(FAMILY_PAGE_RANKED + """
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
                """ + FAMILY_PAGE_WHERE + pageScope.sql() + """
                ORDER BY proposal_number
                LIMIT :limit OFFSET :offset
                """), query, filters), pageScope)
                .param("limit", limit)
                .param("offset", offset)
                .query(ProposalFamilySummaryResponse.class)
                .list();
    }

    /**
     * The database's own account of why these Proposals matched, for the one
     * reason the response cannot carry: the sponsor's ACRONYM.
     *
     * <p>Every other field {@code FAMILY_FREE_TEXT} matches - proposal
     * number, title, sponsor name, lead unit, principal investigator - is on
     * {@link ProposalFamilySummaryResponse} and is explained from it with no
     * query at all. The acronym is reference data on
     * {@code archive.sponsor}, so once the acronym became a retrieval rule a
     * Proposal that matched only through it would carry no caption - the
     * reader would see an NSF-sponsored Proposal with no stated reason.
     *
     * <p>One query per page, and only for the rows that need it. The caller
     * treats a failure as "no explanation available": a caption is a nicety,
     * and losing 25 Proposals because a cosmetic query failed would be a
     * poor trade.
     */
    public List<MatchExplanation> explainProposalMatches(
            Collection<Long> proposalIds,
            String rawQuery,
            boolean anchorWords,
            String wordRegex
    ) {
        if (proposalIds == null || proposalIds.isEmpty()) {
            return List.of();
        }
        return jdbc.sql("""
                SELECT
                    pv.proposal_id AS record_id,
                    'Sponsor' AS field,
                    sp.acronym AS value
                FROM archive.proposal_version pv
                JOIN archive.sponsor sp
                  ON sp.sponsor_code = pv.sponsor_code
                WHERE pv.proposal_id IN (:proposalIds)
                  AND sp.acronym IS NOT NULL
                  AND (
                        (:anchorWords AND sp.acronym ~* :wordRegex)
                     OR (NOT :anchorWords
                         AND sp.acronym ILIKE '%' || :query || '%')
                  )
                """)
                .param("proposalIds", proposalIds)
                .param("query", rawQuery)
                .param("anchorWords", anchorWords)
                .param("wordRegex", wordRegex)
                .query(MatchExplanation.class)
                .list();
    }

    public long countFamilyPage(String query, ProposalSearchFilters filters) {
        return countFamilyPage(query, filters, SqlFragment.NONE);
    }

    /**
     * Must take the same scope as {@link #findFamilyPage}: a total computed
     * over rows the caller cannot see discloses their existence through the
     * page metadata even though no row is returned.
     */
    public long countFamilyPage(String query, ProposalSearchFilters filters, SqlFragment scope) {
        SqlFragment pageScope = scope == null ? SqlFragment.NONE : scope;
        Long count = bindScope(bindFamilyPage(jdbc.sql(FAMILY_PAGE_RANKED + """
                SELECT COUNT(*)
                FROM ranked
                """ + FAMILY_PAGE_WHERE + pageScope.sql()), query, filters), pageScope)
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
