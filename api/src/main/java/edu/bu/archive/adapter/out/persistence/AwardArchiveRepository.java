package edu.bu.archive.adapter.out.persistence;

import edu.bu.archive.adapter.in.web.dto.award.AwardAmountHistoryResponse;
import edu.bu.archive.adapter.in.web.dto.award.AwardAttachmentResponse;
import edu.bu.archive.adapter.in.web.dto.award.AwardCommentResponse;
import edu.bu.archive.adapter.in.web.dto.award.AwardDocumentNumberMatchResponse;
import edu.bu.archive.adapter.in.web.dto.award.AwardFamilySummaryResponse;
import edu.bu.archive.adapter.in.web.dto.award.AwardHierarchyEdgeRow;
import edu.bu.archive.adapter.in.web.dto.award.AwardNotepadEntryResponse;
import edu.bu.archive.adapter.in.web.dto.award.AwardPersonCreditSplitRow;
import edu.bu.archive.adapter.in.web.dto.award.AwardPersonRow;
import edu.bu.archive.adapter.in.web.dto.award.AwardPersonUnitCreditSplitRow;
import edu.bu.archive.adapter.in.web.dto.award.AwardPersonUnitRow;
import edu.bu.archive.adapter.in.web.dto.award.AwardReportTermRecipientRow;
import edu.bu.archive.adapter.in.web.dto.award.AwardReportTermRow;
import edu.bu.archive.adapter.in.web.dto.award.AwardRowResponse;
import edu.bu.archive.adapter.in.web.dto.award.AwardSapTransmissionChildRow;
import edu.bu.archive.adapter.in.web.dto.award.AwardSapTransmissionRow;
import edu.bu.archive.adapter.in.web.dto.award.AwardSearchResultResponse;
import edu.bu.archive.adapter.in.web.dto.award.AwardSequenceSummaryResponse;
import edu.bu.archive.adapter.in.web.dto.award.AwardSponsorTermResponse;
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
                    workflow_document_number AS document_number,
                    modification_number
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

    /*
     * Exact match only (never ILIKE/substring - a workflow document
     * number is an identifier, not free text) against
     * workflow_document_number across every archived Award version -
     * deliberately NOT scoped to is_primary_current, unlike
     * searchAwards/countSearchAwards above, since a workflow document
     * can belong to a superseded (non-current) sequence and must still
     * be findable. documentType is a fixed 'Award' literal for this
     * phase rather than a join to KREW_DOC_TYP_T.DOC_TYP_NM - that join
     * needs KREW_DOC_HDR_T/KREW_DOC_TYP_T archived first, which this
     * phase deliberately defers (see docs/DECISIONS.md) rather than
     * building a generalized workflow-document archive just for this.
     * blank/empty query never matches (workflow_document_number is
     * never blank for a real row, but an equality check against '' is
     * cheap insurance against a wasted index lookup on every keystroke
     * of an empty search box).
     */
    public Optional<AwardDocumentNumberMatchResponse> findExactWorkflowDocumentMatch(
            String rawQuery
    ) {
        if (rawQuery == null || rawQuery.isBlank()) {
            return Optional.empty();
        }

        return jdbc.sql("""
                SELECT
                    award_id,
                    award_number,
                    sequence_number,
                    workflow_document_number,
                    'Award' AS document_type,
                    title,
                    status_description AS status
                FROM archive.award_version
                WHERE workflow_document_number = :rawQuery
                ORDER BY sequence_number DESC
                LIMIT 1
                """)
                .param("rawQuery", rawQuery)
                .query(AwardDocumentNumberMatchResponse.class)
                .optional();
    }

    /*
     * --- People and Units ------------------------------------------------
     *
     * Four targeted queries, all scoped to this one specific award_id
     * (not "current"), assembled into nested AwardPersonDetailResponse
     * objects in AwardArchiveService - none of the three child tables
     * carry award_id in Oracle itself (denormalized at extraction time,
     * see V039's header comment), but all three do here, so each can be
     * queried directly rather than joined.
     */
    public List<AwardPersonRow> findPersonRows(long awardId) {
        return jdbc.sql("""
                SELECT
                    award_person_id,
                    person_id,
                    full_name,
                    contact_role_code,
                    key_person_project_role,
                    academic_year_effort,
                    calendar_year_effort,
                    summer_effort,
                    total_effort
                FROM archive.award_person
                WHERE award_id = :awardId
                ORDER BY
                    CASE
                        WHEN UPPER(TRIM(contact_role_code)) = 'PI'
                        THEN 0
                        ELSE 1
                    END,
                    full_name NULLS LAST,
                    award_person_id
                """)
                .param("awardId", awardId)
                .query(AwardPersonRow.class)
                .list();
    }

    public List<AwardPersonUnitRow> findPersonUnitRows(long awardId) {
        return jdbc.sql("""
                SELECT
                    award_person_unit_id,
                    award_person_id,
                    unit_number,
                    lead_unit_flag
                FROM archive.award_person_unit
                WHERE award_id = :awardId
                ORDER BY award_person_id, award_person_unit_id
                """)
                .param("awardId", awardId)
                .query(AwardPersonUnitRow.class)
                .list();
    }

    public List<AwardPersonCreditSplitRow> findPersonCreditSplitRows(
            long awardId
    ) {
        return jdbc.sql("""
                SELECT
                    award_person_id,
                    inv_credit_type_code,
                    credit
                FROM archive.award_person_credit_split
                WHERE award_id = :awardId
                ORDER BY award_person_id, award_person_credit_split_id
                """)
                .param("awardId", awardId)
                .query(AwardPersonCreditSplitRow.class)
                .list();
    }

    public List<AwardPersonUnitCreditSplitRow> findPersonUnitCreditSplitRows(
            long awardId
    ) {
        return jdbc.sql("""
                SELECT
                    award_person_unit_id,
                    inv_credit_type_code,
                    credit
                FROM archive.award_person_unit_credit_split
                WHERE award_id = :awardId
                ORDER BY
                    award_person_unit_id,
                    award_person_unit_credit_split_id
                """)
                .param("awardId", awardId)
                .query(AwardPersonUnitCreditSplitRow.class)
                .list();
    }

    /*
     * --- Amounts -----------------------------------------------------
     *
     * Unlike the legacy /amounts (scoped to one "current" row), this
     * returns the whole award_number family's amount history, newest
     * first - the same family-wide shape as /versions. award_amount_info
     * has no effective-date column of its own, so award_effective_date
     * is joined in from award_version (see AwardAmountHistoryResponse).
     */
    public long countAmountHistory(String awardNumber) {
        Long count = jdbc.sql("""
                SELECT COUNT(*)
                FROM archive.award_amount_info amount
                INNER JOIN archive.award_version av
                    ON av.award_id = amount.award_id
                WHERE av.award_number = :awardNumber
                """)
                .param("awardNumber", awardNumber)
                .query(Long.class)
                .single();

        return count == null ? 0L : count;
    }

    public List<AwardAmountHistoryResponse> findAmountHistory(
            String awardNumber,
            int limit,
            int offset
    ) {
        return jdbc.sql("""
                SELECT
                    amount.award_amount_info_id,
                    amount.award_id,
                    amount.award_number,
                    amount.sequence_number,
                    amount.obligated_total_direct,
                    amount.obligated_total_indirect,
                    amount.obligated_total_amount,
                    amount.anticipated_change_direct,
                    amount.anticipated_change_indirect,
                    amount.anticipated_total_direct,
                    amount.anticipated_total_indirect,
                    amount.anticipated_total_amount,
                    av.award_effective_date,
                    amount.tnm_document_number AS document_number,
                    amount.source_version_number
                FROM archive.award_amount_info amount
                INNER JOIN archive.award_version av
                    ON av.award_id = amount.award_id
                WHERE av.award_number = :awardNumber
                ORDER BY
                    av.sequence_number DESC,
                    amount.award_amount_info_id DESC
                LIMIT :limit OFFSET :offset
                """)
                .param("awardNumber", awardNumber)
                .param("limit", limit)
                .param("offset", offset)
                .query(AwardAmountHistoryResponse.class)
                .list();
    }

    /*
     * --- Terms ---------------------------------------------------------
     *
     * award_sponsor_term/award_report_term both carry award_id directly
     * (see V040's header comment) - award_report_term_recipient does
     * too, denormalized at extraction time, so it can be queried
     * directly by award_id rather than batched by report-term IDs.
     * Recipients are grouped onto their parent report term in
     * AwardArchiveService.
     */
    public List<AwardSponsorTermResponse> findSponsorTerms(long awardId) {
        return jdbc.sql("""
                SELECT
                    award_sponsor_term_id,
                    sponsor_term_id
                FROM archive.award_sponsor_term
                WHERE award_id = :awardId
                ORDER BY award_sponsor_term_id
                """)
                .param("awardId", awardId)
                .query(AwardSponsorTermResponse.class)
                .list();
    }

    public List<AwardReportTermRow> findReportTermRows(long awardId) {
        return jdbc.sql("""
                SELECT
                    award_report_term_id,
                    report_class_code,
                    report_code,
                    frequency_code,
                    frequency_base_code,
                    osp_distribution_code,
                    due_date
                FROM archive.award_report_term
                WHERE award_id = :awardId
                ORDER BY award_report_term_id
                """)
                .param("awardId", awardId)
                .query(AwardReportTermRow.class)
                .list();
    }

    public List<AwardReportTermRecipientRow> findReportTermRecipientRows(
            long awardId
    ) {
        return jdbc.sql("""
                SELECT
                    award_report_term_recipient_id,
                    award_report_term_id,
                    contact_id,
                    contact_type_code,
                    rolodex_id,
                    number_of_copies
                FROM archive.award_report_term_recipient
                WHERE award_id = :awardId
                ORDER BY award_report_term_id, award_report_term_recipient_id
                """)
                .param("awardId", awardId)
                .query(AwardReportTermRecipientRow.class)
                .list();
    }

    /*
     * --- Comments and Notepad --------------------------------------------
     *
     * award_comment is scoped to this specific award_id (a real
     * sequence_number column). award_notepad has NO sequence_number at
     * all - it is scoped to the whole award_number family (see V042's
     * header comment) - so it is looked up by award_number, not
     * award_id.
     */
    public List<AwardCommentResponse> findComments(long awardId) {
        return jdbc.sql("""
                SELECT
                    award_comment_id,
                    comment_type_code,
                    checklist_print_flag,
                    comments,
                    source_update_timestamp,
                    source_update_user
                FROM archive.award_comment
                WHERE award_id = :awardId
                ORDER BY
                    source_update_timestamp DESC NULLS LAST,
                    award_comment_id DESC
                """)
                .param("awardId", awardId)
                .query(AwardCommentResponse.class)
                .list();
    }

    public List<AwardNotepadEntryResponse> findNotepadEntries(
            String awardNumber
    ) {
        return jdbc.sql("""
                SELECT
                    award_notepad_id,
                    entry_number,
                    note_topic,
                    comments,
                    restricted_view,
                    source_create_timestamp,
                    source_create_user,
                    source_update_timestamp,
                    source_update_user
                FROM archive.award_notepad
                WHERE award_number = :awardNumber
                ORDER BY entry_number DESC, award_notepad_id DESC
                """)
                .param("awardNumber", awardNumber)
                .query(AwardNotepadEntryResponse.class)
                .list();
    }

    /*
     * --- SAP Transmission History -----------------------------------
     *
     * award_id on archive.award_transmission is the ROOT/primary Award
     * of the transmitted hierarchy at the moment of the attempt, and can
     * be reassigned in place by Oracle (see V052's header comment) - so
     * this returns whatever transmissions are currently attributed to
     * this specific award_id, not a family-wide history. Children are
     * fetched in one batched second query and grouped onto their parent
     * transmission in AwardArchiveService, the same two-query pattern
     * used for the hierarchy tree.
     */
    public long countTransmissions(long awardId) {
        Long count = jdbc.sql("""
                SELECT COUNT(*)
                FROM archive.award_transmission
                WHERE award_id = :awardId
                """)
                .param("awardId", awardId)
                .query(Long.class)
                .single();

        return count == null ? 0L : count;
    }

    public List<AwardSapTransmissionRow> findTransmissionRows(
            long awardId,
            int limit,
            int offset
    ) {
        return jdbc.sql("""
                SELECT
                    transmission_id,
                    award_number,
                    sequence_number,
                    initiator_id,
                    transmitter_id,
                    success_indicator,
                    transmission_date,
                    basis_of_payment_code,
                    account_type_code,
                    sponsor_code,
                    method_of_payment_code,
                    document_number,
                    sent_data,
                    returned_data
                FROM archive.award_transmission
                WHERE award_id = :awardId
                ORDER BY
                    transmission_date DESC NULLS LAST,
                    transmission_id DESC
                LIMIT :limit OFFSET :offset
                """)
                .param("awardId", awardId)
                .param("limit", limit)
                .param("offset", offset)
                .query(AwardSapTransmissionRow.class)
                .list();
    }

    public List<AwardSapTransmissionChildRow> findTransmissionChildRows(
            Collection<Long> transmissionIds
    ) {
        if (transmissionIds.isEmpty()) {
            return List.of();
        }

        return jdbc.sql("""
                SELECT
                    transmission_child_id,
                    transmission_id,
                    award_number,
                    sequence_number,
                    parent_document_number,
                    child_document_number,
                    lead_unit_number,
                    child_type,
                    overhead_key,
                    base_code,
                    off_campus
                FROM archive.award_transmission_child
                WHERE transmission_id IN (:transmissionIds)
                ORDER BY transmission_id, transmission_child_id
                """)
                .param("transmissionIds", List.copyOf(transmissionIds))
                .query(AwardSapTransmissionChildRow.class)
                .list();
    }

    /*
     * --- Attachments -----------------------------------------------------
     *
     * Joins the Sprint 1/2 Oracle-direct pair (archive.award_attachment +
     * archive.attachment_object) - see V035/V036, not the older
     * generic archive.archived_attachment table, which this domain does
     * not use. downloadable mirrors the exact UPLOADED + non-blank
     * bucket/key check downloadAttachment() enforces server-side, so a
     * client can decide whether to show a download control without ever
     * seeing s3Bucket/s3Key themselves.
     */
    public List<AwardAttachmentResponse> findAttachments(
            long awardId,
            int limit,
            int offset
    ) {
        return jdbc.sql("""
                SELECT
                    aa.award_attachment_id,
                    aa.award_number,
                    aa.sequence_number,
                    ao.file_name,
                    ao.content_type,
                    aa.description,
                    aa.type_code,
                    aa.document_status_code,
                    ao.file_size_bytes,
                    ao.upload_status,
                    (
                        ao.upload_status = 'UPLOADED'
                        AND ao.s3_bucket IS NOT NULL AND ao.s3_bucket <> ''
                        AND ao.s3_key IS NOT NULL AND ao.s3_key <> ''
                    ) AS downloadable,
                    aa.oracle_update_timestamp
                FROM archive.award_attachment aa
                LEFT JOIN archive.attachment_object ao
                    ON ao.file_id = aa.file_id
                WHERE aa.award_id = :awardId
                ORDER BY
                    aa.oracle_update_timestamp DESC NULLS LAST,
                    aa.award_attachment_id DESC
                LIMIT :limit OFFSET :offset
                """)
                .param("awardId", awardId)
                .param("limit", limit)
                .param("offset", offset)
                .query(AwardAttachmentResponse.class)
                .list();
    }

    public long countAttachments(long awardId) {
        Long count = jdbc.sql("""
                SELECT COUNT(*)
                FROM archive.award_attachment
                WHERE award_id = :awardId
                """)
                .param("awardId", awardId)
                .query(Long.class)
                .single();

        return count == null ? 0L : count;
    }

    public Optional<Long> findAttachmentAwardId(long attachmentId) {
        return jdbc.sql("""
                SELECT award_id
                FROM archive.award_attachment
                WHERE award_attachment_id = :attachmentId
                """)
                .param("attachmentId", attachmentId)
                .query(Long.class)
                .optional();
    }

    public Optional<AwardArchivedAttachment> findArchivedAttachment(
            long awardId,
            long attachmentId
    ) {
        return jdbc.sql("""
                SELECT
                    aa.award_attachment_id,
                    aa.award_id,
                    ao.file_name,
                    ao.content_type,
                    ao.s3_bucket,
                    ao.s3_key,
                    ao.file_size_bytes,
                    ao.upload_status
                FROM archive.award_attachment aa
                LEFT JOIN archive.attachment_object ao
                    ON ao.file_id = aa.file_id
                WHERE aa.award_attachment_id = :attachmentId
                  AND aa.award_id = :awardId
                """)
                .param("attachmentId", attachmentId)
                .param("awardId", awardId)
                .query(AwardArchivedAttachment.class)
                .optional();
    }

}
