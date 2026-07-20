package edu.bu.archive.adapter.out.persistence;

import edu.bu.archive.adapter.in.web.dto.award.AwardFamilySummaryResponse;
import edu.bu.archive.adapter.in.web.dto.award.AwardRowResponse;
import edu.bu.archive.adapter.in.web.dto.award.AwardSequenceSummaryResponse;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

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
                    .AwardUnitContactResponse
            > findCurrentUnitContacts(
                    String awardNumber
            ) {
        return jdbc.sql("""
                SELECT DISTINCT
                    contact.award_unit_contact_id,
                    contact.award_id,
                    contact.award_number,
                    contact.sequence_number,
                    contact.person_id,
                    contact.full_name,
                    contact.unit_number,
                    contact.unit_name,
                    contact.parent_unit_number,
                    contact.parent_unit_name,
                    contact.unit_administrator_type_code,
                    contact.project_role,
                    contact.unit_contact_type,
                    contact.default_unit_contact,
                    contact.primary_title,
                    contact.directory_title,
                    contact.office_location,
                    contact.email_address,
                    contact.office_phone,
                    contact.phone_extension,
                    contact.source_update_timestamp,
                    contact.source_update_user
                FROM archive.award_unit_contact contact
                INNER JOIN archive.award_version award
                    ON award.award_id = contact.award_id
                WHERE award.award_number = :awardNumber
                  AND award.is_primary_current = TRUE
                ORDER BY
                    contact.project_role NULLS LAST,
                    contact.full_name NULLS LAST,
                    contact.unit_number NULLS LAST,
                    contact.award_unit_contact_id
                """)
                .param("awardNumber", awardNumber)
                .query(
                        edu.bu.archive.adapter.in.web.dto.award
                                .AwardUnitContactResponse.class
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

}
