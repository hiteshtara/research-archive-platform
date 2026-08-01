package edu.bu.archive.adapter.out.persistence;

import edu.bu.archive.adapter.in.web.dto.award.AwardFamilySummaryResponse;
import edu.bu.archive.adapter.in.web.dto.award.AwardHierarchyEdgeRow;
import edu.bu.archive.adapter.in.web.dto.award.AwardRowResponse;
import edu.bu.archive.adapter.in.web.dto.award.AwardSearchResultResponse;
import edu.bu.archive.adapter.in.web.dto.award.AwardSequenceSummaryResponse;
import edu.bu.archive.adapter.in.web.dto.award.AwardSummaryCardRow;
import edu.bu.archive.adapter.in.web.dto.award.AwardSummaryResponse;
import edu.bu.archive.adapter.in.web.dto.award.AwardVersionSummaryResponse;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Repository
public class AwardArchiveRepository {

    private final JdbcClient jdbc;

    public AwardArchiveRepository(
            JdbcClient jdbc
    ) {
        this.jdbc = jdbc;
    }

    public List<AwardFamilySummaryResponse> findFamilies(
            String query,
            int limit
    ) {
        String normalizedQuery =
                query == null
                        ? ""
                        : query.trim();

        return jdbc.sql("""
                SELECT
                    award_number,
                    title,
                    status_description AS status,
                    award_sequence_status,
                    sponsor_name AS sponsor,
                    lead_unit_name AS lead_unit,
                    account_number,
                    sequence_number AS latest_sequence_number,
                    award_id AS primary_award_id
                FROM archive.award_version
                WHERE is_primary_current = TRUE
                  AND (
                        :query = ''
                        OR award_number ILIKE '%' || :query || '%'
                        OR title ILIKE '%' || :query || '%'
                        OR sponsor_name ILIKE '%' || :query || '%'
                        OR lead_unit_name ILIKE '%' || :query || '%'
                        OR account_number ILIKE '%' || :query || '%'
                  )
                ORDER BY award_number
                LIMIT :limit
                """)
                .param("query", normalizedQuery)
                .param("limit", limit)
                .query(AwardFamilySummaryResponse.class)
                .list();
    }

    public Optional<AwardRowResponse> findCurrent(
            String awardNumber
    ) {
        return jdbc.sql("""
                SELECT
                    award_id,
                    award_number,
                    sequence_number,
                    title,
                    status_description AS status,
                    award_sequence_status,
                    sponsor_name AS sponsor,
                    prime_sponsor_name AS prime_sponsor,
                    lead_unit_name AS lead_unit,
                    account_number,
                    sponsor_award_number,
                    begin_date,
                    closeout_date,
                    is_current_version AS current,
                    is_primary_current AS primary_current
                FROM archive.award_version
                WHERE award_number = :awardNumber
                  AND is_primary_current = TRUE
                LIMIT 1
                """)
                .param("awardNumber", awardNumber)
                .query(AwardRowResponse.class)
                .optional();
    }

    public long countSequences(
            String awardNumber
    ) {
        Long count = jdbc.sql("""
                SELECT COUNT(DISTINCT sequence_number)
                FROM archive.award_version
                WHERE award_number = :awardNumber
                """)
                .param("awardNumber", awardNumber)
                .query(Long.class)
                .single();

        return count == null ? 0L : count;
    }

    public List<AwardSequenceSummaryResponse> findSequenceSummaries(
            String awardNumber,
            int limit,
            int offset
    ) {
        return jdbc.sql("""
                WITH ranked AS (
                    SELECT
                        award_id,
                        sequence_number,
                        status_description,
                        award_sequence_status,

                        COUNT(*) OVER (
                            PARTITION BY sequence_number
                        ) AS row_count,

                        BOOL_OR(is_current_version) OVER (
                            PARTITION BY sequence_number
                        ) AS current_sequence,

                        ROW_NUMBER() OVER (
                            PARTITION BY sequence_number
                            ORDER BY
                                CASE
                                    WHEN UPPER(
                                        TRIM(award_sequence_status)
                                    ) = 'ACTIVE'
                                    THEN 1
                                    ELSE 0
                                END DESC,
                                source_update_timestamp DESC NULLS LAST,
                                award_id DESC
                        ) AS row_rank
                    FROM archive.award_version
                    WHERE award_number = :awardNumber
                )
                SELECT
                    sequence_number,
                    status_description AS status,
                    award_sequence_status,
                    current_sequence,
                    row_count,
                    award_id AS representative_award_id
                FROM ranked
                WHERE row_rank = 1
                ORDER BY sequence_number DESC
                LIMIT :limit
                OFFSET :offset
                """)
                .param("awardNumber", awardNumber)
                .param("limit", limit)
                .param("offset", offset)
                .query(AwardSequenceSummaryResponse.class)
                .list();
    }

    public List<AwardRowResponse> findSequenceRows(
            String awardNumber,
            int sequenceNumber
    ) {
        return jdbc.sql("""
                SELECT
                    award_id,
                    award_number,
                    sequence_number,
                    title,
                    status_description AS status,
                    award_sequence_status,
                    sponsor_name AS sponsor,
                    prime_sponsor_name AS prime_sponsor,
                    lead_unit_name AS lead_unit,
                    account_number,
                    sponsor_award_number,
                    begin_date,
                    closeout_date,
                    is_current_version AS current,
                    is_primary_current AS primary_current
                FROM archive.award_version
                WHERE award_number = :awardNumber
                  AND sequence_number = :sequenceNumber
                ORDER BY
                    CASE
                        WHEN UPPER(
                            TRIM(award_sequence_status)
                        ) = 'ACTIVE'
                        THEN 1
                        ELSE 0
                    END DESC,
                    source_update_timestamp DESC NULLS LAST,
                    award_id DESC
                """)
                .param("awardNumber", awardNumber)
                .param("sequenceNumber", sequenceNumber)
                .query(AwardRowResponse.class)
                .list();
    }

    /*
     * Existing proof-of-concept history method.
     * Keep temporarily so the current UI continues working.
     */
    public List<AwardRowResponse> findHistoryRows(
            String awardNumber
    ) {
        return jdbc.sql("""
                SELECT
                    award_id,
                    award_number,
                    sequence_number,
                    title,
                    status_description AS status,
                    award_sequence_status,
                    sponsor_name AS sponsor,
                    prime_sponsor_name AS prime_sponsor,
                    lead_unit_name AS lead_unit,
                    account_number,
                    sponsor_award_number,
                    begin_date,
                    closeout_date,
                    is_current_version AS current,
                    is_primary_current AS primary_current
                FROM archive.award_version
                WHERE award_number = :awardNumber
                ORDER BY
                    sequence_number DESC,
                    CASE
                        WHEN UPPER(
                            TRIM(award_sequence_status)
                        ) = 'ACTIVE'
                        THEN 1
                        ELSE 0
                    END DESC,
                    source_update_timestamp DESC NULLS LAST,
                    award_id DESC
                """)
                .param("awardNumber", awardNumber)
                .query(AwardRowResponse.class)
                .list();
    }


    public List<edu.bu.archive.adapter.in.web.dto.award.AwardPersonResponse>
            findCurrentPeople(
                    String awardNumber
            ) {
        return jdbc.sql("""
                SELECT
                    person.award_person_id,
                    person.award_id,
                    person.award_number,
                    person.sequence_number,
                    person.person_id,
                    person.rolodex_id,
                    person.full_name,
                    person.contact_role_code,
                    person.key_person_project_role,
                    person.faculty_flag,
                    person.academic_year_effort,
                    person.calendar_year_effort,
                    person.summer_effort,
                    person.total_effort,
                    person.source_update_timestamp,
                    person.source_update_user
                FROM archive.award_person person
                INNER JOIN archive.award_version award
                    ON award.award_id = person.award_id
                WHERE award.award_number = :awardNumber
                  AND award.is_primary_current = TRUE
                ORDER BY
                    CASE
                        WHEN UPPER(
                            TRIM(person.contact_role_code)
                        ) = 'PI'
                        THEN 0
                        ELSE 1
                    END,
                    person.full_name NULLS LAST,
                    person.award_person_id
                """)
                .param("awardNumber", awardNumber)
                .query(
                        edu.bu.archive.adapter.in.web.dto.award
                                .AwardPersonResponse.class
                )
                .list();
    }


    public List<
            edu.bu.archive.adapter.in.web.dto.award
                    .AwardAmountResponse
            > findCurrentAmounts(
                    String awardNumber
            ) {
        return jdbc.sql("""
                SELECT
                    amount.award_amount_info_id,
                    amount.award_id,
                    amount.award_number,
                    amount.sequence_number,
                    amount.anticipated_change_direct,
                    amount.anticipated_change_indirect,
                    amount.anticipated_total_direct,
                    amount.anticipated_total_indirect,
                    amount.obligated_total_direct,
                    amount.obligated_total_indirect,
                    amount.anticipated_total_amount,
                    amount.obligated_total_amount,
                    amount.tnm_document_number,
                    amount.source_version_number
                FROM archive.award_amount_info amount
                INNER JOIN archive.award_version award
                    ON award.award_id = amount.award_id
                WHERE award.award_number = :awardNumber
                  AND award.is_primary_current = TRUE
                ORDER BY
                    amount.source_version_number DESC NULLS LAST,
                    amount.award_amount_info_id DESC
                """)
                .param("awardNumber", awardNumber)
                .query(
                        edu.bu.archive.adapter.in.web.dto.award
                                .AwardAmountResponse.class
                )
                .list();
    }

    public List<
            edu.bu.archive.adapter.in.web.dto.award
                    .AwardProposalResponse
            > findCurrentProposals(
                    String awardNumber
            ) {
        return jdbc.sql("""
                SELECT
                    proposal.award_funding_proposal_id,
                    proposal.award_id,
                    proposal.proposal_id,
                    proposal.active_flag,
                    proposal.source_update_timestamp,
                    proposal.source_update_user,
                    proposal.source_version_number
                FROM archive.award_funding_proposal proposal
                INNER JOIN archive.award_version award
                    ON award.award_id = proposal.award_id
                WHERE award.award_number = :awardNumber
                ORDER BY
                    CASE
                        WHEN UPPER(
                            TRIM(proposal.active_flag)
                        ) IN ('Y', 'YES', 'TRUE', '1')
                        THEN 0
                        ELSE 1
                    END,
                    proposal.proposal_id,
                    proposal.award_funding_proposal_id
                """)
                .param("awardNumber", awardNumber)
                .query(
                        edu.bu.archive.adapter.in.web.dto.award
                                .AwardProposalResponse.class
                )
                .list();
    }

    /*
     * --- Search --------------------------------------------------------
     *
     * pattern is an already-normalized ILIKE pattern (see
     * AwardSearchPattern) - a single bound parameter, never concatenated
     * into the SQL text. rawQuery is the untouched, trimmed user input,
     * used only for the exact-award-number fast path and the "no query
     * at all" empty-string check.
     *
     * PI/person name and current obligated amount are resolved via
     * LEFT JOIN LATERAL, one representative row per award_id, rather
     * than a plain JOIN - a plain join would multiply rows whenever an
     * Award has more than one person/amount row, breaking pagination
     * counts. LATERAL keeps this a single targeted query with a small,
     * bounded per-row aggregation instead of one massive multi-table
     * join. archive.award_hierarchy is joined only for the root/parent
     * indicator - most Awards have no hierarchy row at all, hence LEFT
     * JOIN.
     */
    public List<AwardSearchResultResponse> searchAwards(
            String pattern,
            String rawQuery,
            int limit,
            int offset
    ) {
        return jdbc.sql("""
                SELECT
                    av.award_id,
                    av.award_number,
                    av.sequence_number AS latest_sequence_number,
                    av.title,
                    av.status_description AS status,
                    pi.full_name AS principal_investigator,
                    av.sponsor_name AS sponsor,
                    av.lead_unit_name AS lead_unit,
                    amt.obligated_total_amount AS current_obligated_amount,
                    ah.root_award_number,
                    ah.parent_award_number
                FROM archive.award_version av
                LEFT JOIN LATERAL (
                    SELECT ap.full_name
                    FROM archive.award_person ap
                    WHERE ap.award_id = av.award_id
                    ORDER BY
                        CASE
                            WHEN UPPER(TRIM(ap.contact_role_code)) = 'PI'
                            THEN 0
                            ELSE 1
                        END,
                        ap.full_name NULLS LAST,
                        ap.award_person_id
                    LIMIT 1
                ) pi ON TRUE
                LEFT JOIN LATERAL (
                    SELECT ai.obligated_total_amount
                    FROM archive.award_amount_info ai
                    WHERE ai.award_id = av.award_id
                    ORDER BY
                        ai.source_version_number DESC NULLS LAST,
                        ai.award_amount_info_id DESC
                    LIMIT 1
                ) amt ON TRUE
                LEFT JOIN archive.award_hierarchy ah
                    ON ah.award_number = av.award_number
                WHERE av.is_primary_current = TRUE
                  AND (
                        :rawQuery = ''
                        OR UPPER(av.award_number) = UPPER(:rawQuery)
                        OR av.award_number ILIKE :pattern
                        OR av.title ILIKE :pattern
                        OR av.sponsor_code ILIKE :pattern
                        OR av.sponsor_name ILIKE :pattern
                        OR av.lead_unit_number ILIKE :pattern
                        OR av.lead_unit_name ILIKE :pattern
                        OR av.modification_number ILIKE :pattern
                        OR EXISTS (
                            SELECT 1 FROM archive.award_person ap2
                            WHERE ap2.award_id = av.award_id
                              AND ap2.full_name ILIKE :pattern
                        )
                  )
                ORDER BY av.award_number
                LIMIT :limit OFFSET :offset
                """)
                .param("rawQuery", rawQuery)
                .param("pattern", pattern)
                .param("limit", limit)
                .param("offset", offset)
                .query(AwardSearchResultResponse.class)
                .list();
    }

    public long countSearchAwards(String pattern, String rawQuery) {
        Long count = jdbc.sql("""
                SELECT COUNT(*)
                FROM archive.award_version av
                WHERE av.is_primary_current = TRUE
                  AND (
                        :rawQuery = ''
                        OR UPPER(av.award_number) = UPPER(:rawQuery)
                        OR av.award_number ILIKE :pattern
                        OR av.title ILIKE :pattern
                        OR av.sponsor_code ILIKE :pattern
                        OR av.sponsor_name ILIKE :pattern
                        OR av.lead_unit_number ILIKE :pattern
                        OR av.lead_unit_name ILIKE :pattern
                        OR av.modification_number ILIKE :pattern
                        OR EXISTS (
                            SELECT 1 FROM archive.award_person ap2
                            WHERE ap2.award_id = av.award_id
                              AND ap2.full_name ILIKE :pattern
                        )
                  )
                """)
                .param("rawQuery", rawQuery)
                .param("pattern", pattern)
                .query(Long.class)
                .single();

        return count == null ? 0L : count;
    }

    /*
     * --- Hierarchy -------------------------------------------------------
     *
     * Two targeted queries, not one massive join: the hierarchy edge
     * list for the whole family in one query, then a single batched
     * summary-card lookup for every award_number in that family - the
     * tree itself is built in Java (see AwardArchiveService), which is
     * also where malformed-data handling (cycles, dangling parent
     * references) lives.
     */
    public Optional<String> findHierarchyRoot(String awardNumber) {
        return jdbc.sql("""
                SELECT root_award_number
                FROM archive.award_hierarchy
                WHERE award_number = :awardNumber
                LIMIT 1
                """)
                .param("awardNumber", awardNumber)
                .query(String.class)
                .optional();
    }

    public List<AwardHierarchyEdgeRow> findHierarchyEdges(
            String rootAwardNumber
    ) {
        return jdbc.sql("""
                SELECT
                    root_award_number,
                    award_number,
                    parent_award_number,
                    active
                FROM archive.award_hierarchy
                WHERE root_award_number = :rootAwardNumber
                ORDER BY award_number
                """)
                .param("rootAwardNumber", rootAwardNumber)
                .query(AwardHierarchyEdgeRow.class)
                .list();
    }

    public List<AwardSummaryCardRow> findSummaryCards(
            Collection<String> awardNumbers
    ) {
        if (awardNumbers.isEmpty()) {
            return List.of();
        }

        return jdbc.sql("""
                SELECT
                    av.award_number,
                    av.award_id,
                    av.sequence_number AS latest_sequence_number,
                    av.title,
                    av.status_description AS status,
                    pi.full_name AS principal_investigator,
                    av.sponsor_name AS sponsor,
                    av.lead_unit_name AS lead_unit,
                    amt.obligated_total_amount AS current_obligated_amount
                FROM archive.award_version av
                LEFT JOIN LATERAL (
                    SELECT ap.full_name
                    FROM archive.award_person ap
                    WHERE ap.award_id = av.award_id
                    ORDER BY
                        CASE
                            WHEN UPPER(TRIM(ap.contact_role_code)) = 'PI'
                            THEN 0
                            ELSE 1
                        END,
                        ap.full_name NULLS LAST,
                        ap.award_person_id
                    LIMIT 1
                ) pi ON TRUE
                LEFT JOIN LATERAL (
                    SELECT ai.obligated_total_amount
                    FROM archive.award_amount_info ai
                    WHERE ai.award_id = av.award_id
                    ORDER BY
                        ai.source_version_number DESC NULLS LAST,
                        ai.award_amount_info_id DESC
                    LIMIT 1
                ) amt ON TRUE
                WHERE av.is_primary_current = TRUE
                  AND av.award_number IN (:awardNumbers)
                """)
                .param("awardNumbers", List.copyOf(awardNumbers))
                .query(AwardSummaryCardRow.class)
                .list();
    }

    /*
     * --- Summary -----------------------------------------------------
     *
     * Keyed by the surrogate award_id (a specific version), not
     * award_number - deliberately different from every other Award
     * endpoint in this repository, per the required endpoint shape
     * (GET /api/awards/{awardId}/summary).
     */
    public Optional<AwardSummaryResponse> findSummaryByAwardId(long awardId) {
        return jdbc.sql("""
                SELECT
                    av.award_id,
                    av.award_number,
                    av.sequence_number,
                    av.title,
                    av.status_description AS status,
                    av.sponsor_name AS sponsor,
                    av.prime_sponsor_name AS prime_sponsor,
                    pi.full_name AS principal_investigator,
                    av.lead_unit_name AS lead_unit,
                    av.award_effective_date,
                    av.award_execution_date,
                    av.begin_date,
                    av.closeout_date,
                    amt.obligated_total_amount,
                    amt.anticipated_total_amount,
                    av.basis_of_payment_code,
                    av.basis_of_payment_description,
                    av.method_of_payment_code,
                    av.method_of_payment_description,
                    ah.root_award_number,
                    ah.parent_award_number
                FROM archive.award_version av
                LEFT JOIN LATERAL (
                    SELECT ap.full_name
                    FROM archive.award_person ap
                    WHERE ap.award_id = av.award_id
                    ORDER BY
                        CASE
                            WHEN UPPER(TRIM(ap.contact_role_code)) = 'PI'
                            THEN 0
                            ELSE 1
                        END,
                        ap.full_name NULLS LAST,
                        ap.award_person_id
                    LIMIT 1
                ) pi ON TRUE
                LEFT JOIN LATERAL (
                    SELECT
                        ai.obligated_total_amount,
                        ai.anticipated_total_amount
                    FROM archive.award_amount_info ai
                    WHERE ai.award_id = av.award_id
                    ORDER BY
                        ai.source_version_number DESC NULLS LAST,
                        ai.award_amount_info_id DESC
                    LIMIT 1
                ) amt ON TRUE
                LEFT JOIN archive.award_hierarchy ah
                    ON ah.award_number = av.award_number
                WHERE av.award_id = :awardId
                """)
                .param("awardId", awardId)
                .query(AwardSummaryResponse.class)
                .optional();
    }

    /*
     * --- Versions ------------------------------------------------------
     *
     * Keyed by the surrogate award_id in the URL, but the response is
     * every version of that award_id's own award_number family -
     * findAwardNumberForId resolves the family first.
     */
    public Optional<String> findAwardNumberForId(long awardId) {
        return jdbc.sql("""
                SELECT award_number
                FROM archive.award_version
                WHERE award_id = :awardId
                LIMIT 1
                """)
                .param("awardId", awardId)
                .query(String.class)
                .optional();
    }

    public List<AwardVersionSummaryResponse> findVersionSummaries(
            String awardNumber,
            int limit,
            int offset
    ) {
        return jdbc.sql("""
                SELECT
                    award_id,
                    award_number,
                    sequence_number,
                    status_description AS status,
                    transaction_type_code,
                    transaction_type,
                    award_effective_date,
                    source_update_timestamp AS update_timestamp,
                    modification_number AS document_number
                FROM archive.award_version
                WHERE award_number = :awardNumber
                ORDER BY
                    sequence_number DESC,
                    source_update_timestamp DESC NULLS LAST,
                    award_id DESC
                LIMIT :limit OFFSET :offset
                """)
                .param("awardNumber", awardNumber)
                .param("limit", limit)
                .param("offset", offset)
                .query(AwardVersionSummaryResponse.class)
                .list();
    }

    public long countVersions(String awardNumber) {
        Long count = jdbc.sql("""
                SELECT COUNT(*)
                FROM archive.award_version
                WHERE award_number = :awardNumber
                """)
                .param("awardNumber", awardNumber)
                .query(Long.class)
                .single();

        return count == null ? 0L : count;
    }

}
