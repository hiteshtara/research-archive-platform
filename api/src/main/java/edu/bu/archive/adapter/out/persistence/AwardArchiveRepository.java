package edu.bu.archive.adapter.out.persistence;

import edu.bu.archive.adapter.in.web.dto.award.AwardAmountHistoryResponse;
import edu.bu.archive.adapter.in.web.dto.award.AwardAssociatedNegotiationResponse;
import edu.bu.archive.adapter.in.web.dto.award.AwardAttachmentResponse;
import edu.bu.archive.adapter.in.web.dto.award.AwardFundingProposalResponse;
import edu.bu.archive.adapter.in.web.dto.award.AwardFundingSubawardResponse;
import edu.bu.archive.adapter.in.web.dto.award.AwardBudgetLineItemResponse;
import edu.bu.archive.adapter.in.web.dto.award.AwardBudgetPeriodResponse;
import edu.bu.archive.adapter.in.web.dto.award.AwardBudgetPersonnelResponse;
import edu.bu.archive.adapter.in.web.dto.award.AwardBudgetRow;
import edu.bu.archive.adapter.in.web.dto.award.AwardCentralAdministrationContactResponse;
import edu.bu.archive.adapter.in.web.dto.award.AwardCommentRow;
import edu.bu.archive.adapter.in.web.dto.award.AwardDocumentNumberMatchResponse;
import edu.bu.archive.adapter.in.web.dto.award.AwardFamilyPositionRow;
import edu.bu.archive.adapter.in.web.dto.award.AwardFamilySummaryResponse;
import edu.bu.archive.adapter.in.web.dto.explorer.ExplorerAwardResponse;
import edu.bu.archive.adapter.in.web.dto.explorer.ExplorerPersonResponse;
import edu.bu.archive.adapter.in.web.dto.explorer.ExplorerProposalDiscoveryResponse;
import edu.bu.archive.adapter.in.web.dto.explorer.ExplorerRolodexResponse;
import edu.bu.archive.adapter.in.web.dto.explorer.ExplorerUnitAdministratorResponse;
import edu.bu.archive.adapter.in.web.dto.explorer.ExplorerUnitRow;
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
import edu.bu.archive.adapter.in.web.dto.award.AwardSponsorContactResponse;
import edu.bu.archive.adapter.in.web.dto.award.AwardSponsorTermResponse;
import edu.bu.archive.adapter.in.web.dto.award.AwardSummaryCardRow;
import edu.bu.archive.adapter.in.web.dto.award.AwardSummaryResponse;
import edu.bu.archive.adapter.in.web.dto.award.AwardUnitContactResponse;
import edu.bu.archive.adapter.in.web.dto.award.AwardUnitDetailsResponse;
import edu.bu.archive.adapter.in.web.dto.award.AwardVersionSearchResultResponse;
import edu.bu.archive.adapter.in.web.dto.award.AwardVersionSummaryResponse;
import edu.bu.archive.adapter.in.web.dto.award.TimeAndMoneyActionResponse;
import edu.bu.archive.adapter.in.web.dto.award.TimeAndMoneyDocumentResponse;
import edu.bu.archive.adapter.in.web.dto.award.TimeAndMoneyHistoryEntryResponse;
import edu.bu.archive.adapter.in.web.dto.award.TimeAndMoneySummaryResponse;
import edu.bu.archive.adapter.in.web.dto.award.TimeAndMoneyTransactionDetailResponse;
import edu.bu.archive.adapter.in.web.dto.award.TimeAndMoneyTransactionHeaderRow;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.LocalDate;
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
     * The bidirectional counterpart to
     * ProposalV1Repository.findFundedAwardRows - one row per real
     * archive.award_funding_proposal relationship row, family-wide
     * (every award_id in this Award's whole award_number family).
     * Inactive relationships are still returned - never silently
     * dropped. linked_proposal resolves proposal.proposal_id directly
     * (the EXACT historical Proposal version stored in the
     * relationship - kept only for exactLinkedProposalId/
     * linkedProposalVersion); display fields and the navigation target
     * instead resolve through linked_proposal.proposal_number to that
     * family's ACTIVE version (PROPOSAL_SEQUENCE_STATUS = 'ACTIVE' -
     * never the highest sequence number) - a LEFT JOIN since a
     * malformed family could in principle have no row marked ACTIVE.
     */
    public List<AwardFundingProposalResponse> findFundingProposalRows(
            String awardNumber
    ) {
        return jdbc.sql("""
                SELECT
                    linked_proposal.proposal_number,
                    active_proposal.title AS proposal_title,
                    active_proposal.status_description AS proposal_status,
                    active_proposal.document_number AS workflow_document_number,
                    active_proposal.principal_investigator_name,
                    active_proposal.sponsor_name,
                    active_proposal.initial_total_cost AS requested_total_cost,
                    linked_proposal.version_number AS linked_proposal_version,
                    active_proposal.version_number AS active_proposal_version,
                    (
                        UPPER(TRIM(proposal.active_flag))
                        IN ('Y', 'YES', 'TRUE', '1')
                    ) AS relationship_active,
                    proposal.proposal_id AS exact_linked_proposal_id,
                    active_proposal.proposal_id AS navigable_active_proposal_id
                FROM archive.award_funding_proposal proposal
                INNER JOIN archive.award_version award
                    ON award.award_id = proposal.award_id
                JOIN archive.proposal_version linked_proposal
                    ON linked_proposal.proposal_id = proposal.proposal_id
                LEFT JOIN archive.proposal_version active_proposal
                    ON active_proposal.proposal_number
                        = linked_proposal.proposal_number
                    AND active_proposal.proposal_sequence_status = 'ACTIVE'
                WHERE award.award_number = :awardNumber
                ORDER BY
                    CASE
                        WHEN UPPER(TRIM(proposal.active_flag))
                            IN ('Y', 'YES', 'TRUE', '1')
                        THEN 0
                        ELSE 1
                    END,
                    linked_proposal.proposal_number,
                    proposal.proposal_id
                """)
                .param("awardNumber", awardNumber)
                .query(AwardFundingProposalResponse.class)
                .list();
    }

    /*
     * The bidirectional counterpart to
     * SubawardArchiveRepository.findFunding - one row per real
     * archive.subaward_funding relationship row, family-wide (matched
     * on the award_number denormalized onto subaward_funding at ETL
     * time, not by joining through a specific award_id - see
     * oracle/subaward/export_subaward_funding.sql). A Subaward funding
     * source has no active/inactive flag in the source schema (unlike
     * award_funding_proposal), so every row here is a real, standing
     * relationship - never filtered. linked_subaward resolves
     * funding.subaward_id directly (the EXACT historical Subaward
     * version stored in the relationship); display fields and the
     * navigation target instead resolve through
     * linked_subaward.subaward_code to that family's ACTIVE version
     * (subaward_sequence_status = 'ACTIVE' - never the highest
     * sequence number) - a LEFT JOIN since a malformed family could in
     * principle have no row marked ACTIVE. subaward_amount mirrors the
     * Amounts tab's History of Changes total (sum of
     * archive.subaward_amount.obligated_change for the resolved
     * current version).
     */
    public List<AwardFundingSubawardResponse> findFundingSubawardRows(
            String awardNumber
    ) {
        return jdbc.sql("""
                SELECT
                    linked_subaward.subaward_code,
                    current_subaward.organization_id,
                    current_subaward.status_description AS subaward_status,
                    current_subaward.document_number AS workflow_document_number,
                    amt.total_obligated_change AS subaward_amount,
                    linked_subaward.sequence_number AS linked_subaward_version,
                    current_subaward.sequence_number AS current_subaward_version,
                    funding.subaward_id AS exact_linked_subaward_id,
                    current_subaward.subaward_id AS navigable_current_subaward_id
                FROM archive.subaward_funding funding
                JOIN archive.subaward linked_subaward
                    ON linked_subaward.subaward_id = funding.subaward_id
                LEFT JOIN archive.subaward current_subaward
                    ON current_subaward.subaward_code
                        = linked_subaward.subaward_code
                    AND current_subaward.subaward_sequence_status = 'ACTIVE'
                LEFT JOIN LATERAL (
                    SELECT SUM(sa.obligated_change) AS total_obligated_change
                    FROM archive.subaward_amount sa
                    WHERE sa.subaward_id = current_subaward.subaward_id
                ) amt ON TRUE
                WHERE funding.award_number = :awardNumber
                ORDER BY linked_subaward.subaward_code,
                    funding.subaward_funding_source_id
                """)
                .param("awardNumber", awardNumber)
                .query(AwardFundingSubawardResponse.class)
                .list();
    }

    /*
     * The Negotiation(s) associated with this Award - family-wide, from
     * archive.negotiation. Unlike Award<->Subaward, Negotiation has no
     * version/family concept of its own (source_version_number is an
     * Oracle optimistic-lock stamp, not a sequence) - each negotiation_id
     * is its own root record, always directly navigable, no "current
     * version" resolution needed. associated_document_id for
     * negotiation_association_type_code = 'AWD' rows already stores the
     * shared award_number business key (see
     * NegotiationArchiveRepository.resolveCurrentAwardId for the forward
     * direction), so this reverse query is family-wide with no join to
     * archive.award_version required.
     */
    public List<AwardAssociatedNegotiationResponse> findAssociatedNegotiationRows(
            String awardNumber
    ) {
        return jdbc.sql("""
                SELECT
                    negotiation_id,
                    document_number,
                    negotiation_status_description,
                    negotiation_agreement_type_description,
                    negotiator_full_name,
                    negotiation_start_date,
                    negotiation_end_date
                FROM archive.negotiation
                WHERE negotiation_association_type_code = 'AWD'
                    AND associated_document_id = :awardNumber
                ORDER BY negotiation_start_date DESC NULLS LAST,
                    negotiation_id DESC
                """)
                .param("awardNumber", awardNumber)
                .query(AwardAssociatedNegotiationResponse.class)
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
                    ah.parent_award_number,
                    av.is_primary_current AS primary_current,
                    av.workflow_document_number AS document_number
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
                    modification_number,
                    is_primary_current AS primary_current
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
     * --- Version-level search (Historical Award Records) -----------------
     *
     * One row per award_id, deliberately NEVER scoped to
     * is_primary_current - the whole point of this query is to surface
     * both current and historical versions side by side (primaryCurrent
     * distinguishes them). awardNumber/documentNumber are exact-match
     * sentinels ('' means "no filter"), matching searchAwards' own
     * :rawQuery = '' convention - both hit real indexes
     * (ix_award_version_family_sequence/ix_award_version_number for
     * award_number, ix_award_version_workflow_document_number for
     * document_number; see V011/V012/V055). versionFilter is a fixed,
     * server-validated enum ("all"/"current"/"historical"), never raw
     * user text, so comparing it by ordinary equality is safe. sortSql
     * is likewise never user-supplied - AwardArchiveService picks one of
     * two fixed literal ORDER BY clauses before calling this method: a
     * date-unfiltered sort has no supporting index today (flagged, not
     * fixed, per the investigation - archive.award_version has none on
     * source_update_timestamp alone) and may be slower at full scale
     * than the sequence-based default.
     */
    public List<AwardVersionSearchResultResponse> searchAwardVersions(
            String pattern,
            String rawQuery,
            String awardNumber,
            String documentNumber,
            String versionFilter,
            String sortSql,
            int limit,
            int offset
    ) {
        return jdbc.sql("""
                SELECT
                    av.award_id,
                    av.award_number,
                    av.sequence_number,
                    av.workflow_document_number AS document_number,
                    av.title,
                    av.status_description AS status,
                    av.sponsor_name AS sponsor,
                    pi.full_name AS principal_investigator,
                    av.lead_unit_name AS lead_unit,
                    av.award_effective_date,
                    av.source_update_timestamp AS update_timestamp,
                    av.is_primary_current AS primary_current
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
                WHERE (:awardNumber = '' OR UPPER(av.award_number) = UPPER(:awardNumber))
                  AND (:documentNumber = '' OR UPPER(av.workflow_document_number) = UPPER(:documentNumber))
                  AND (
                        :rawQuery = ''
                        OR av.title ILIKE :pattern
                        OR av.sponsor_name ILIKE :pattern
                        OR av.lead_unit_name ILIKE :pattern
                        OR EXISTS (
                            SELECT 1 FROM archive.award_person ap2
                            WHERE ap2.award_id = av.award_id
                              AND ap2.full_name ILIKE :pattern
                        )
                  )
                  AND (
                        :versionFilter = 'all'
                        OR (:versionFilter = 'current' AND av.is_primary_current = TRUE)
                        OR (:versionFilter = 'historical' AND av.is_primary_current = FALSE)
                  )
                """ + sortSql + """
                LIMIT :limit OFFSET :offset
                """)
                .param("pattern", pattern)
                .param("rawQuery", rawQuery)
                .param("awardNumber", awardNumber)
                .param("documentNumber", documentNumber)
                .param("versionFilter", versionFilter)
                .param("limit", limit)
                .param("offset", offset)
                .query(AwardVersionSearchResultResponse.class)
                .list();
    }

    public long countSearchAwardVersions(
            String pattern,
            String rawQuery,
            String awardNumber,
            String documentNumber,
            String versionFilter
    ) {
        Long count = jdbc.sql("""
                SELECT COUNT(*)
                FROM archive.award_version av
                WHERE (:awardNumber = '' OR UPPER(av.award_number) = UPPER(:awardNumber))
                  AND (:documentNumber = '' OR UPPER(av.workflow_document_number) = UPPER(:documentNumber))
                  AND (
                        :rawQuery = ''
                        OR av.title ILIKE :pattern
                        OR av.sponsor_name ILIKE :pattern
                        OR av.lead_unit_name ILIKE :pattern
                        OR EXISTS (
                            SELECT 1 FROM archive.award_person ap2
                            WHERE ap2.award_id = av.award_id
                              AND ap2.full_name ILIKE :pattern
                        )
                  )
                  AND (
                        :versionFilter = 'all'
                        OR (:versionFilter = 'current' AND av.is_primary_current = TRUE)
                        OR (:versionFilter = 'historical' AND av.is_primary_current = FALSE)
                  )
                """)
                .param("pattern", pattern)
                .param("rawQuery", rawQuery)
                .param("awardNumber", awardNumber)
                .param("documentNumber", documentNumber)
                .param("versionFilter", versionFilter)
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
     * --- Unit Details / Contacts (shared reference model) -----------------
     *
     * Award reuses archive.unit/unit_administrator/unit_administrator_type/
     * rolodex/person - never a second, Award-owned copy of this data (see
     * database/migrations/V056 and docs/architecture/AWARD_CONTACTS_DESIGN.md).
     * Central Administration Contacts is DERIVED (reproduces
     * Award.initCentralAdminContacts() exactly - lead unit's
     * administrators filtered to default_group_flag='C'); Unit Contacts
     * and Sponsor Contacts are real, already-archived Award-specific
     * data (archive.award_unit_contact/archive.award_sponsor_contact),
     * only joined against the shared reference tables for display
     * fields (person contact info, rolodex organization/phone/email)
     * neither Award-owned table carries itself.
     */
    public Optional<AwardUnitDetailsResponse> findUnitDetails(long awardId) {
        return jdbc.sql("""
                SELECT
                    u.unit_number,
                    u.unit_name,
                    u.parent_unit_number,
                    parent.unit_name AS parent_unit_name,
                    u.organization_id AS organization,
                    TRUE AS lead_unit
                FROM archive.award_version av
                JOIN archive.unit u ON u.unit_number = av.lead_unit_number
                LEFT JOIN archive.unit parent
                       ON parent.unit_number = u.parent_unit_number
                WHERE av.award_id = :awardId
                """)
                .param("awardId", awardId)
                .query(AwardUnitDetailsResponse.class)
                .optional();
    }

    public List<AwardCentralAdministrationContactResponse>
            findCentralAdministrationContacts(long awardId) {
        return jdbc.sql("""
                SELECT
                    ua.person_id,
                    COALESCE(pe.full_name, ua.person_id) AS full_name,
                    uat.description AS project_role,
                    pe.email_address AS email,
                    pe.phone_number AS phone
                FROM archive.award_version av
                JOIN archive.unit_administrator ua
                     ON ua.unit_number = av.lead_unit_number
                JOIN archive.unit_administrator_type uat
                     ON uat.unit_administrator_type_code
                        = ua.unit_administrator_type_code
                LEFT JOIN archive.person pe ON pe.person_id = ua.person_id
                WHERE av.award_id = :awardId
                  AND uat.default_group_flag = 'C'
                ORDER BY ua.unit_administrator_type_code, ua.person_id
                """)
                .param("awardId", awardId)
                .query(AwardCentralAdministrationContactResponse.class)
                .list();
    }

    public List<AwardUnitContactResponse> findUnitContacts(long awardId) {
        return jdbc.sql("""
                SELECT
                    auc.person_id,
                    auc.full_name,
                    COALESCE(uat.description, auc.unit_administrator_type_code)
                        AS project_role,
                    auc.unit_administrator_unit_number AS unit_number,
                    COALESCE(
                        auc.unit_administrator_unit_number
                            = av.lead_unit_number,
                        FALSE
                    ) AS lead_unit,
                    pe.email_address AS email,
                    pe.phone_number AS phone
                FROM archive.award_unit_contact auc
                JOIN archive.award_version av ON av.award_id = auc.award_id
                LEFT JOIN archive.unit_administrator_type uat
                       ON uat.unit_administrator_type_code
                          = auc.unit_administrator_type_code
                LEFT JOIN archive.person pe ON pe.person_id = auc.person_id
                WHERE auc.award_id = :awardId
                ORDER BY auc.award_unit_contact_id
                """)
                .param("awardId", awardId)
                .query(AwardUnitContactResponse.class)
                .list();
    }

    public List<AwardSponsorContactResponse> findSponsorContacts(
            long awardId
    ) {
        return jdbc.sql("""
                SELECT
                    asc_.full_name,
                    r.organization,
                    asc_.contact_role_code,
                    r.email_address AS email,
                    r.phone_number AS phone
                FROM archive.award_sponsor_contact asc_
                LEFT JOIN archive.rolodex r ON r.rolodex_id = asc_.rolodex_id
                WHERE asc_.award_id = :awardId
                ORDER BY asc_.award_sponsor_contact_id
                """)
                .param("awardId", awardId)
                .query(AwardSponsorContactResponse.class)
                .list();
    }

    /*
     * --- Archive Explorer (Phase 2 - see docs/ARCHIVE_EXPLORER.md) ---------
     *
     * Reuses the exact query shapes already proven live in Phase 1's
     * Python CLI (etl/archive_etl/explorer.py) - never a new, unproven
     * SQL shape. Runs in-process against the same PostgreSQL connection
     * every other repository method uses (no per-request Fargate task,
     * unlike Phase 1's ECS-mediated CLI). unit_number/rolodex_id/
     * person_id/award_number/document_number lookups are standalone
     * (never Award-scoped), unlike findUnitDetails/
     * findCentralAdministrationContacts/findUnitContacts above.
     */
    private static final String EXPLORER_AWARD_SELECT = """
            SELECT
                av.award_id,
                av.award_number,
                av.sequence_number,
                av.title,
                av.status_description AS status,
                pi.full_name AS principal_investigator,
                av.workflow_document_number,
                av.modification_number,
                av.lead_unit_number,
                av.lead_unit_name,
                av.is_primary_current AS primary_current
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
            """;

    public Optional<ExplorerAwardResponse> findExplorerAwardByNumber(
            String awardNumber
    ) {
        return jdbc.sql(
                        EXPLORER_AWARD_SELECT
                                + "WHERE av.award_number = :awardNumber "
                                + "AND av.is_primary_current = TRUE"
                )
                .param("awardNumber", awardNumber)
                .query(ExplorerAwardResponse.class)
                .optional();
    }

    public Optional<ExplorerAwardResponse> findExplorerAwardVersionById(
            long awardId
    ) {
        return jdbc.sql(EXPLORER_AWARD_SELECT + "WHERE av.award_id = :awardId")
                .param("awardId", awardId)
                .query(ExplorerAwardResponse.class)
                .optional();
    }

    public Optional<ExplorerUnitRow> findUnitByNumber(String unitNumber) {
        return jdbc.sql("""
                SELECT
                    u.unit_number,
                    u.unit_name,
                    u.parent_unit_number,
                    parent.unit_name AS parent_unit_name,
                    u.organization_id AS organization
                FROM archive.unit u
                LEFT JOIN archive.unit parent
                       ON parent.unit_number = u.parent_unit_number
                WHERE u.unit_number = :unitNumber
                """)
                .param("unitNumber", unitNumber)
                .query(ExplorerUnitRow.class)
                .optional();
    }

    public List<ExplorerUnitAdministratorResponse>
            findUnitAdministratorsByUnitNumber(String unitNumber) {
        return jdbc.sql("""
                SELECT
                    ua.person_id,
                    COALESCE(pe.full_name, ua.person_id) AS full_name,
                    ua.unit_administrator_type_code AS administrator_type_code,
                    uat.description AS administrator_type_description,
                    uat.default_group_flag,
                    pe.email_address AS email,
                    pe.phone_number AS phone
                FROM archive.unit_administrator ua
                LEFT JOIN archive.unit_administrator_type uat
                       ON uat.unit_administrator_type_code
                          = ua.unit_administrator_type_code
                LEFT JOIN archive.person pe ON pe.person_id = ua.person_id
                WHERE ua.unit_number = :unitNumber
                ORDER BY ua.unit_administrator_type_code, ua.person_id
                """)
                .param("unitNumber", unitNumber)
                .query(ExplorerUnitAdministratorResponse.class)
                .list();
    }

    public Optional<ExplorerPersonResponse> findExplorerPersonById(
            String personId
    ) {
        return jdbc.sql("""
                SELECT
                    person_id,
                    first_name,
                    middle_name,
                    last_name,
                    full_name,
                    email_address AS email,
                    phone_number AS phone
                FROM archive.person
                WHERE person_id = :personId
                """)
                .param("personId", personId)
                .query(ExplorerPersonResponse.class)
                .optional();
    }

    public Optional<ExplorerRolodexResponse> findExplorerRolodexById(
            long rolodexId
    ) {
        return jdbc.sql("""
                SELECT
                    rolodex_id,
                    first_name,
                    last_name,
                    organization,
                    phone_number AS phone,
                    email_address AS email,
                    city,
                    state,
                    active
                FROM archive.rolodex
                WHERE rolodex_id = :rolodexId
                """)
                .param("rolodexId", rolodexId)
                .query(ExplorerRolodexResponse.class)
                .optional();
    }

    /*
     * "Sponsors" as its own Explorer resource means the current-version
     * Awards carrying a given SPONSOR_CODE. archive.rolodex has no
     * sponsor_code column at all (confirmed against
     * information_schema/V056 - an earlier version of this method
     * wrongly assumed one existed and threw
     * "column sponsor_code does not exist" against the real dev
     * database); sponsor_code/sponsor_name are instead denormalized
     * directly onto archive.award_version (see AwardArchiveRepository's
     * existing sponsor ILIKE search above, and
     * ix_award_version_sponsor - V053), so this reuses
     * EXPLORER_AWARD_SELECT rather than inventing a new join. A
     * different concept from an Award's own Sponsor Contacts
     * (archive.award_sponsor_contact, already covered by
     * findSponsorContacts/the /award-contacts aggregate above).
     */
    public List<ExplorerAwardResponse> findAwardsBySponsorCode(
            String sponsorCode
    ) {
        return jdbc.sql(
                        EXPLORER_AWARD_SELECT
                                + "WHERE av.sponsor_code = :sponsorCode "
                                + "AND av.is_primary_current = TRUE "
                                + "ORDER BY av.award_number"
                )
                .param("sponsorCode", sponsorCode)
                .query(ExplorerAwardResponse.class)
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
     * award_comment has a real, backfilled sequence_number column (a
     * specific Award version owns each row), but Kuali's own
     * AwardCommentServiceImpl.retrieveCommentHistoryByType() looks up
     * comment history by awardNumber across the WHOLE version family,
     * not by award_id - the same family-wide scoping fix already
     * applied to Time and Money (see findTimeAndMoneySummary's header
     * comment above). award_notepad has NO sequence_number at all - it
     * is scoped to the whole award_number family (see V042's header
     * comment) - so it is looked up by award_number too, for a
     * different reason.
     *
     * Starts FROM archive.comment_type (not award_comment) and LEFT
     * JOINs award_comment, so every screen_flag='Y' comment type is
     * represented even when this Award family has zero comments of
     * that type - AwardArchiveService turns that all-null row into a
     * "no comment recorded" category rather than omitting it.
     * award_comment_screen_flag='Y' reproduces Kuali's own
     * retrieveCommentTypes() filter (e.g. code 21, "Current Action
     * Comments", is screen_flag='N' and real archived data, but never
     * shown on this screen).
     */
    public List<AwardCommentRow> findComments(String awardNumber) {
        return jdbc.sql("""
                SELECT
                    ct.comment_type_code,
                    ct.description AS comment_type_description,
                    ac.award_comment_id,
                    ac.award_id,
                    ac.sequence_number,
                    av.workflow_document_number,
                    ac.comments,
                    ac.source_update_timestamp,
                    ac.source_update_user
                FROM archive.comment_type ct
                LEFT JOIN archive.award_comment ac
                    ON ac.comment_type_code = ct.comment_type_code
                    AND ac.award_number = :awardNumber
                LEFT JOIN archive.award_version av
                    ON av.award_id = ac.award_id
                WHERE ct.award_comment_screen_flag = 'Y'
                ORDER BY
                    ct.comment_type_code,
                    ac.sequence_number DESC,
                    ac.source_update_timestamp DESC NULLS LAST,
                    ac.award_comment_id DESC
                """)
                .param("awardNumber", awardNumber)
                .query(AwardCommentRow.class)
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

    /*
     * --- Time and Money (see docs/architecture/AWARD_TIME_AND_MONEY_DESIGN.md) ---
     *
     * Every method below reads only already-archived tables (V048/V049)
     * - no new migration, no new ETL.
     *
     * Scoping model (proven against TimeAndMoneyHistoryServiceImpl.java
     * in the real Kuali source, and against live data across several
     * ordinary Awards - see the "Time and Money scoping" investigation
     * this comment summarizes): Kuali's own Time and Money History
     * screen is entered by awardNumber
     * (buildTimeAndMoneyHistoryObjects(String awardNumber, ...)), not
     * by a single award_id - it walks every version in the family. A
     * single Award VERSION's own award_amount_info rows are frequently
     * NOT the version that was actually Time-and-Money-touched (a
     * plain, non-T&M amendment can mint a new "current" version whose
     * only row is a copy-forward snapshot, while the real T&M activity
     * sits on an earlier sequence) - confirmed live: 3 of 4 sampled
     * ordinary Awards had zero Time-and-Money-created rows on their
     * current version despite real family history.
     *
     * findTimeAndMoneySummary therefore splits its scope in two:
     *   - obligated/anticipated totals stay scoped to this EXACT
     *     award_id's own latest award_amount_info row (genuinely
     *     version-specific financial state, correct as version-scoped -
     *     this part was never the bug).
     *   - "last action"/transaction count search the WHOLE award_number
     *     family via archive.award_amount_transaction, matching
     *     findTimeAndMoneyActions/findTimeAndMoneyHistory's own
     *     family-wide scope exactly (award_amount_transaction has no
     *     sequence_number column in real Kuali at all, so there is no
     *     version to lose by doing this).
     */

    public Optional<TimeAndMoneySummaryResponse> findTimeAndMoneySummary(
            long awardId
    ) {
        return jdbc.sql("""
                SELECT
                    amount.award_id,
                    amount.award_number,
                    av.sequence_number,
                    amount.obligated_total_amount,
                    amount.obligated_total_direct,
                    amount.obligated_total_indirect,
                    amount.anticipated_total_amount,
                    amount.anticipated_total_direct,
                    amount.anticipated_total_indirect,
                    family_count.count AS family_transaction_count,
                    last_action.document_number AS last_family_time_and_money_document_number,
                    last_action.notice_date AS last_family_notice_date,
                    last_action.transaction_type_description AS last_family_transaction_type_description
                FROM archive.award_amount_info amount
                INNER JOIN archive.award_version av ON av.award_id = amount.award_id
                LEFT JOIN LATERAL (
                    SELECT COUNT(*) AS count
                    FROM archive.award_amount_transaction aat
                    WHERE aat.award_number = amount.award_number
                ) family_count ON TRUE
                LEFT JOIN LATERAL (
                    SELECT
                        aat.document_number,
                        aat.notice_date,
                        aat.transaction_type_description
                    FROM archive.award_amount_transaction aat
                    WHERE aat.award_number = amount.award_number
                    ORDER BY
                        aat.notice_date DESC NULLS LAST,
                        aat.award_amount_transaction_id DESC
                    LIMIT 1
                ) last_action ON TRUE
                WHERE amount.award_id = :awardId
                ORDER BY amount.award_amount_info_id DESC
                LIMIT 1
                """)
                .param("awardId", awardId)
                .query(TimeAndMoneySummaryResponse.class)
                .optional();
    }

    public long countTimeAndMoneyActions(String awardNumber) {
        Long count = jdbc.sql("""
                SELECT COUNT(*)
                FROM archive.award_amount_transaction
                WHERE award_number = :awardNumber
                """)
                .param("awardNumber", awardNumber)
                .query(Long.class)
                .single();

        return count == null ? 0L : count;
    }

    public List<TimeAndMoneyActionResponse> findTimeAndMoneyActions(
            String awardNumber,
            int limit,
            int offset
    ) {
        return jdbc.sql("""
                SELECT
                    aat.award_amount_transaction_id,
                    aat.award_number,
                    aat.document_number AS time_and_money_document_number,
                    aat.transaction_type_code,
                    aat.transaction_type_description,
                    aat.notice_date,
                    aat.comments,
                    tmd.document_status,
                    tmd.creation_date,
                    aat.source_update_user,
                    aat.source_update_timestamp
                FROM archive.award_amount_transaction aat
                LEFT JOIN archive.time_and_money_document tmd
                    ON tmd.document_number = aat.document_number
                WHERE aat.award_number = :awardNumber
                ORDER BY
                    aat.notice_date DESC NULLS LAST,
                    aat.award_amount_transaction_id DESC
                LIMIT :limit OFFSET :offset
                """)
                .param("awardNumber", awardNumber)
                .param("limit", limit)
                .param("offset", offset)
                .query(TimeAndMoneyActionResponse.class)
                .list();
    }

    public List<TimeAndMoneyHistoryEntryResponse> findTimeAndMoneyHistory(
            String awardNumber,
            int limit,
            int offset
    ) {
        return jdbc.sql("""
                SELECT
                    amount.award_amount_info_id,
                    amount.award_id,
                    amount.award_number,
                    av.sequence_number,
                    amount.transaction_id AS pending_transaction_id,
                    amount.tnm_document_number AS time_and_money_document_number,
                    amount.originating_award_version,
                    amount.obligated_total_direct,
                    amount.obligated_total_indirect,
                    amount.obligated_total_amount,
                    amount.anticipated_change_direct,
                    amount.anticipated_change_indirect,
                    amount.anticipated_total_direct,
                    amount.anticipated_total_indirect,
                    amount.anticipated_total_amount,
                    av.award_effective_date,
                    (
                        amount.transaction_id IS NOT NULL
                        AND amount.tnm_document_number IS NOT NULL
                    ) AS time_and_money_created
                FROM archive.award_amount_info amount
                INNER JOIN archive.award_version av ON av.award_id = amount.award_id
                WHERE av.award_number = :awardNumber
                ORDER BY
                    av.sequence_number DESC,
                    amount.award_amount_info_id DESC
                LIMIT :limit OFFSET :offset
                """)
                .param("awardNumber", awardNumber)
                .param("limit", limit)
                .param("offset", offset)
                .query(TimeAndMoneyHistoryEntryResponse.class)
                .list();
    }

    public Optional<TimeAndMoneyTransactionHeaderRow> findTimeAndMoneyTransactionHeader(
            long pendingTransactionId
    ) {
        return jdbc.sql("""
                SELECT
                    pt.transaction_id AS pending_transaction_id,
                    pt.document_number AS time_and_money_document_number,
                    pt.source_award_number,
                    pt.destination_award_number,
                    pt.obligated_amount,
                    pt.obligated_direct_amount,
                    pt.obligated_indirect_amount,
                    pt.anticipated_amount,
                    pt.anticipated_direct_amount,
                    pt.anticipated_indirect_amount,
                    pt.comments,
                    pt.processed_flag,
                    pte.budget_period AS fanda_distribution_period
                FROM archive.pending_transaction pt
                LEFT JOIN archive.pending_transaction_extension pte
                    ON pte.transaction_id = pt.transaction_id
                WHERE pt.transaction_id = :pendingTransactionId
                """)
                .param("pendingTransactionId", pendingTransactionId)
                .query(TimeAndMoneyTransactionHeaderRow.class)
                .optional();
    }

    public List<TimeAndMoneyTransactionDetailResponse> findTimeAndMoneyTransactionDetails(
            long pendingTransactionId
    ) {
        return jdbc.sql("""
                SELECT
                    td.transaction_detail_id,
                    td.award_number,
                    td.sequence_number,
                    td.time_and_money_document_number,
                    td.source_award_number,
                    td.destination_award_number,
                    td.obligated_amount,
                    td.obligated_direct_amount,
                    td.obligated_indirect_amount,
                    td.anticipated_amount,
                    td.anticipated_direct_amount,
                    td.anticipated_indirect_amount,
                    td.comments,
                    td.transaction_detail_type
                FROM archive.transaction_detail td
                WHERE td.transaction_id = :pendingTransactionId
                ORDER BY td.transaction_detail_id
                """)
                .param("pendingTransactionId", pendingTransactionId)
                .query(TimeAndMoneyTransactionDetailResponse.class)
                .list();
    }

    public Optional<TimeAndMoneyDocumentResponse> findTimeAndMoneyDocument(
            String timeAndMoneyDocumentNumber
    ) {
        return jdbc.sql("""
                SELECT
                    document_number AS time_and_money_document_number,
                    root_award_number,
                    document_status,
                    creation_date
                FROM archive.time_and_money_document
                WHERE document_number = :documentNumber
                """)
                .param("documentNumber", timeAndMoneyDocumentNumber)
                .query(TimeAndMoneyDocumentResponse.class)
                .optional();
    }

    /*
     * --- Budget (see docs/kuali-business-rules/Budget.md) -----------------
     *
     * Proven live against real Oracle/archive data before any of this
     * was written: budget_version_number is a family-wide monotonic
     * counter, not scoped to one award_id - Kuali's own
     * Award.getBudgets()/AwardBudgetServiceImpl.getAllBudgetsForAward
     * resolve every Budget across the whole award_number family,
     * bounded to sequences <= the Award version being viewed. Every
     * query below follows that same bound; none of them ever filter to
     * a single exact award_id the way most other Award child tables in
     * this project correctly do.
     */

    public Optional<AwardFamilyPositionRow> findFamilyPositionForId(
            long awardId
    ) {
        return jdbc.sql("""
                SELECT award_number, sequence_number
                FROM archive.award_version
                WHERE award_id = :awardId
                """)
                .param("awardId", awardId)
                .query(AwardFamilyPositionRow.class)
                .optional();
    }

    /*
     * Every Budget in scope for (awardNumber, viewedSequenceNumber),
     * newest budget_version_number first. Shared by both the Summary
     * endpoint (picks one row via the Posted-then-non-Cancelled rule)
     * and the Versions endpoint (returns all of them with a computed
     * "selected" flag) - one query, two presentations, avoiding a
     * second near-identical SQL statement.
     */
    public List<AwardBudgetRow> findBudgetsInScope(
            String awardNumber,
            int viewedSequenceNumber
    ) {
        return jdbc.sql("""
                SELECT
                    ab.budget_id,
                    ab.award_id AS owning_award_id,
                    av.sequence_number AS owning_award_sequence_number,
                    ab.budget_version_number,
                    ab.document_number AS workflow_document_number,
                    ab.award_budget_status_code AS status_code,
                    ab.award_budget_status_description AS status_description,
                    ab.start_date,
                    ab.end_date,
                    ab.total_direct_cost,
                    ab.total_indirect_cost,
                    ab.total_cost,
                    ab.obligated_total AS award_budget_total_cost_limit,
                    ab.total_cost_limit AS budget_change_total_cost_limit
                FROM archive.award_budget ab
                JOIN archive.award_version av ON av.award_id = ab.award_id
                WHERE av.award_number = :awardNumber
                    AND av.sequence_number <= :viewedSequenceNumber
                ORDER BY ab.budget_version_number DESC
                """)
                .param("awardNumber", awardNumber)
                .param("viewedSequenceNumber", viewedSequenceNumber)
                .query(AwardBudgetRow.class)
                .list();
    }

    public List<AwardBudgetPeriodResponse> findBudgetPeriods(long budgetId) {
        return jdbc.sql("""
                SELECT
                    budget_period_id,
                    budget_period AS period_number,
                    start_date,
                    end_date,
                    total_direct_cost,
                    total_indirect_cost,
                    total_cost
                FROM archive.award_budget_period
                WHERE budget_id = :budgetId
                ORDER BY budget_period, budget_period_id
                """)
                .param("budgetId", budgetId)
                .query(AwardBudgetPeriodResponse.class)
                .list();
    }

    public long countBudgetLineItems(long budgetId) {
        Long count = jdbc.sql("""
                SELECT COUNT(*)
                FROM archive.award_budget_line_item bli
                JOIN archive.award_budget_period bp
                    ON bp.budget_period_id = bli.budget_period_id
                WHERE bp.budget_id = :budgetId
                """)
                .param("budgetId", budgetId)
                .query(Long.class)
                .single();

        return count == null ? 0L : count;
    }

    public List<AwardBudgetLineItemResponse> findBudgetLineItems(
            long budgetId,
            int limit,
            int offset
    ) {
        return jdbc.sql("""
                SELECT
                    bli.budget_line_item_id,
                    bli.budget_period_id,
                    bli.line_item_number,
                    bli.line_item_description AS description,
                    bli.cost_element,
                    bli.start_date,
                    bli.end_date,
                    bli.line_item_cost,
                    bli.cost_sharing_amount
                FROM archive.award_budget_line_item bli
                JOIN archive.award_budget_period bp
                    ON bp.budget_period_id = bli.budget_period_id
                WHERE bp.budget_id = :budgetId
                ORDER BY bp.budget_period, bli.line_item_number, bli.budget_line_item_id
                LIMIT :limit OFFSET :offset
                """)
                .param("budgetId", budgetId)
                .param("limit", limit)
                .param("offset", offset)
                .query(AwardBudgetLineItemResponse.class)
                .list();
    }

    public long countBudgetPersonnel(long budgetId) {
        Long count = jdbc.sql("""
                SELECT COUNT(*)
                FROM archive.award_budget_personnel_detail
                WHERE budget_id = :budgetId
                """)
                .param("budgetId", budgetId)
                .query(Long.class)
                .single();

        return count == null ? 0L : count;
    }

    /*
     * archive.award_budget_person is the budget-level personnel
     * roster (name/appointment type), joined in by (budget_id,
     * person_sequence_number) - the same composite key Oracle itself
     * uses (see V051's header comment). calculated_salary sums every
     * real, persisted award_budget_personnel_calculated_amount row for
     * this personnel line - Oracle stores one row per rate
     * application (rate_class_code/rate_type_code), never a single
     * "calculated salary" field, so this is a sum of real stored
     * numbers across however many rate applications exist, not a
     * computed/invented figure. NULL (not zero) when no calculated-
     * amount rows exist at all, via SUM's own null-on-no-rows behavior
     * combined with the LEFT JOIN.
     */
    public List<AwardBudgetPersonnelResponse> findBudgetPersonnel(
            long budgetId,
            int limit,
            int offset
    ) {
        return jdbc.sql("""
                SELECT
                    bpd.budget_personnel_line_item_id AS budget_person_id,
                    bpd.person_id,
                    abp.person_name AS full_name,
                    bpd.job_code,
                    abp.appointment_type_code AS appointment_type,
                    bpd.salary_requested AS base_salary,
                    calc.calculated_salary
                FROM archive.award_budget_personnel_detail bpd
                LEFT JOIN archive.award_budget_person abp
                    ON abp.budget_id = bpd.budget_id
                    AND abp.person_sequence_number = bpd.person_sequence_number
                LEFT JOIN LATERAL (
                    SELECT SUM(bpca.calculated_cost) AS calculated_salary
                    FROM archive.award_budget_personnel_calculated_amount bpca
                    WHERE bpca.budget_personnel_line_item_id
                        = bpd.budget_personnel_line_item_id
                ) calc ON TRUE
                WHERE bpd.budget_id = :budgetId
                ORDER BY bpd.budget_personnel_line_item_id
                LIMIT :limit OFFSET :offset
                """)
                .param("budgetId", budgetId)
                .param("limit", limit)
                .param("offset", offset)
                .query(AwardBudgetPersonnelResponse.class)
                .list();
    }

    /*
     * --- Proposal Discovery (Explorer) --------------------------------
     *
     * Multi-filter, relational-only (no embeddings/vector search, no
     * arbitrary SQL) discovery query rooted at the ACTIVE version of
     * every Institutional Proposal family (pv.proposal_sequence_status
     * = 'ACTIVE' - the same grain ProposalV1Repository/
     * AwardArchiveRepository already treat as "the" current Proposal
     * version elsewhere in this codebase). One row per Proposal, never
     * fanned out:
     *
     *   - attachment_count: a LATERAL COUNT(*), not a join, so a
     *     Proposal with N attachments doesn't multiply its own row.
     *   - linked_award: LATERAL-picks at most one active
     *     archive.proposal_award relationship per Proposal (ORDER BY
     *     source_update_timestamp DESC, award_funding_proposal_id DESC)
     *     - a deliberate discovery-tool simplification; a Proposal can
     *       genuinely have multiple simultaneously-active funded-Award
     *       relationships (live-verified: Proposal 01109910 has 3), but
     *       this endpoint surfaces one representative link per row, not
     *       the full relationship list (see
     *       ProposalV1Controller's /funded-awards for that).
     *   - current_award: resolves linked_award.award_number to that
     *       family's CURRENT version (is_primary_current = TRUE) -
     *       exactLinkedAwardId (linked_award.award_id) is kept for
     *       audit only, never itself navigated to.
     *   - current_amount: a LATERAL pick of current_award's own latest
     *       archive.award_amount_info row (ORDER BY
     *       award_amount_info_id DESC LIMIT 1) - the same "authoritative
     *       single snapshot, never join every historical amount row"
     *       technique already proven in findTimeAndMoneySummary, so a
     *       multi-row amount history can never duplicate a Proposal's
     *       own result row.
     *
     * Every filter parameter is nullable and short-circuits via
     * "(CAST(:param AS type) IS NULL OR ...)" - omitting a filter means
     * "don't filter on it", never "match nothing". The explicit CAST on
     * every one of these (including the plain String filters, not just
     * the Boolean/numeric ones) is required, not decorative - live-
     * verified against real Postgres that a bare ":sponsorCode IS NULL
     * OR pv.sponsor_code = :sponsorCode" throws
     * psycopg.errors.AmbiguousParameter ("could not determine data type
     * of parameter") when the value is null, even though the same
     * parameter is also compared against a VARCHAR column elsewhere in
     * the same statement - Postgres does not reliably infer the type
     * from that second usage. minimumAwardAmount compares against
     * COALESCE(obligated, anticipated) - obligated is authoritative once
     * an Award has real obligation activity; anticipated is the
     * pre-obligation estimate used before that.
     *
     * principal_investigator: LATERAL-picks archive.proposal_person
     * where contact_role_code = 'PI' - live-verified zero proposal_ids
     * have more than one PI row (a plain LEFT JOIN would already be
     * safe), but LATERAL + LIMIT 1 is kept for defense in depth against
     * that invariant ever being violated by a future load. sponsorName
     * is denormalized directly on proposal_version, no join needed.
     * sponsorName/personName filters are ILIKE substring matches (not
     * exact), because sponsor identity in this Oracle data is genuinely
     * fragmented - live-verified NIH alone spans ~15 distinct
     * institute-specific sponsor_codes (NCI, NHLBI, NIAID, ...), so a
     * caller searching "NIH" needs a name-substring match, not a single
     * exact code; NSF by contrast is a single real code (301573).
     * dateFrom/dateTo filter by overlap with the proposal's own project
     * period (initial_start_date/total_end_date), not by
     * source_update_timestamp - see
     * docs/kuali-business-rules/HISTORICAL_PERSON_PARTICIPATION.md §6
     * for why "recorded" vs. "project period" are different questions;
     * this endpoint answers the project-period question, matching what
     * a research administrator actually means by a date range here.
     */
    private static final String PROPOSAL_DISCOVERY_SELECT = """
            SELECT
                pv.proposal_id,
                pv.proposal_number,
                pv.title AS proposal_title,
                pv.document_number AS workflow_document_number,
                pv.sponsor_name AS sponsor_name,
                pi.full_name AS principal_investigator_name,
                CAST(COALESCE(att.attachment_count, 0) AS INTEGER) AS attachment_count,
                linked_award.award_number AS linked_award_number,
                current_award.award_id AS navigable_current_award_id,
                current_award.title AS award_title,
                current_amount.obligated_total_amount AS obligated_amount,
                current_amount.anticipated_total_amount AS anticipated_amount,
                linked_award.award_id AS exact_linked_award_id
            FROM archive.proposal_version pv
            LEFT JOIN LATERAL (
                SELECT COUNT(*) AS attachment_count
                FROM archive.proposal_attachment pat
                WHERE pat.proposal_id = pv.proposal_id
            ) att ON TRUE
            LEFT JOIN LATERAL (
                SELECT pp.full_name
                FROM archive.proposal_person pp
                WHERE pp.proposal_id = pv.proposal_id
                  AND pp.contact_role_code = 'PI'
                ORDER BY pp.proposal_person_id
                LIMIT 1
            ) pi ON TRUE
            LEFT JOIN LATERAL (
                SELECT pa.award_id, pa.award_number
                FROM archive.proposal_award pa
                WHERE pa.proposal_id = pv.proposal_id
                  AND pa.active = TRUE
                ORDER BY
                    pa.source_update_timestamp DESC NULLS LAST,
                    pa.award_funding_proposal_id DESC NULLS LAST
                LIMIT 1
            ) linked_award ON TRUE
            LEFT JOIN archive.award_version current_award
                ON current_award.award_number = linked_award.award_number
                AND current_award.is_primary_current = TRUE
            LEFT JOIN LATERAL (
                SELECT amount.obligated_total_amount, amount.anticipated_total_amount
                FROM archive.award_amount_info amount
                WHERE amount.award_id = current_award.award_id
                ORDER BY amount.award_amount_info_id DESC
                LIMIT 1
            ) current_amount ON TRUE
            WHERE pv.proposal_sequence_status = 'ACTIVE'
              AND (
                    CAST(:hasAttachments AS boolean) IS NULL
                    OR (
                        CAST(:hasAttachments AS boolean) = TRUE
                        AND COALESCE(att.attachment_count, 0) > 0
                    )
                    OR (
                        CAST(:hasAttachments AS boolean) = FALSE
                        AND COALESCE(att.attachment_count, 0) = 0
                    )
              )
              AND (
                    CAST(:hasFundedAward AS boolean) IS NULL
                    OR (
                        CAST(:hasFundedAward AS boolean) = TRUE
                        AND linked_award.award_number IS NOT NULL
                    )
                    OR (
                        CAST(:hasFundedAward AS boolean) = FALSE
                        AND linked_award.award_number IS NULL
                    )
              )
              AND (
                    CAST(:minimumAwardAmount AS numeric) IS NULL
                    OR COALESCE(
                        current_amount.obligated_total_amount,
                        current_amount.anticipated_total_amount
                    ) > CAST(:minimumAwardAmount AS numeric)
              )
              AND (
                    CAST(:sponsorCode AS varchar) IS NULL
                    OR pv.sponsor_code = CAST(:sponsorCode AS varchar)
              )
              AND (
                    CAST(:sponsorName AS varchar) IS NULL
                    OR pv.sponsor_name ILIKE '%' || CAST(:sponsorName AS varchar) || '%'
              )
              AND (
                    CAST(:leadUnitNumber AS varchar) IS NULL
                    OR pv.lead_unit_number = CAST(:leadUnitNumber AS varchar)
              )
              AND (
                    CAST(:proposalStatus AS varchar) IS NULL
                    OR pv.status_description = CAST(:proposalStatus AS varchar)
              )
              AND (
                    CAST(:personName AS varchar) IS NULL
                    OR pi.full_name ILIKE '%' || CAST(:personName AS varchar) || '%'
              )
              AND (
                    CAST(:proposalType AS varchar) IS NULL
                    OR pv.proposal_type = CAST(:proposalType AS varchar)
              )
              AND (
                    CAST(:activityType AS varchar) IS NULL
                    OR pv.activity_type = CAST(:activityType AS varchar)
              )
              AND (
                    CAST(:dateFrom AS date) IS NULL
                    OR pv.total_end_date IS NULL
                    OR pv.total_end_date >= CAST(:dateFrom AS date)
              )
              AND (
                    CAST(:dateTo AS date) IS NULL
                    OR pv.initial_start_date IS NULL
                    OR pv.initial_start_date <= CAST(:dateTo AS date)
              )
            ORDER BY pv.proposal_number
            LIMIT :limit OFFSET :offset
            """;

    public List<ExplorerProposalDiscoveryResponse> findProposalDiscoveryRows(
            Boolean hasAttachments,
            Boolean hasFundedAward,
            BigDecimal minimumAwardAmount,
            String sponsorCode,
            String sponsorName,
            String leadUnitNumber,
            String proposalStatus,
            String personName,
            String proposalType,
            String activityType,
            LocalDate dateFrom,
            LocalDate dateTo,
            int limit,
            int offset
    ) {
        return jdbc.sql(PROPOSAL_DISCOVERY_SELECT)
                .param("hasAttachments", hasAttachments)
                .param("hasFundedAward", hasFundedAward)
                .param("minimumAwardAmount", minimumAwardAmount)
                .param("sponsorCode", sponsorCode)
                .param("sponsorName", sponsorName)
                .param("leadUnitNumber", leadUnitNumber)
                .param("proposalStatus", proposalStatus)
                .param("personName", personName)
                .param("proposalType", proposalType)
                .param("activityType", activityType)
                .param("dateFrom", dateFrom)
                .param("dateTo", dateTo)
                .param("limit", limit)
                .param("offset", offset)
                .query(ExplorerProposalDiscoveryResponse.class)
                .list();
    }

}
