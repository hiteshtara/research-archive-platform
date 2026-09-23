package edu.bu.archive.adapter.in.web.dto.award;

import java.math.BigDecimal;
import java.time.LocalDate;

/*
 * Compact Award summary only - no comments, Budget, Time and Money, SAP
 * transmission history, or attachments (those are separate, later
 * section endpoints - see AWARD_SEARCH_API_DESIGN.md's Phase 2 scope).
 *
 * Field names here follow the archive/source semantics; the Kuali
 * business LABELS live in the UI (AwardSummarySection.tsx), which is
 * where BU staff read them. The two must not be conflated:
 *
 *   awardEffectiveDate  is labelled "Project Start Date"
 *   obligationStartDate is labelled "Obligation Start Date"
 *
 * WHY awardEffectiveDate IS "Project Start Date" and begin_date IS NOT:
 * Kuali's Award screen renders AWARD.AWARD_EFFECTIVE_DATE for Project
 * Start Date. AWARD.BEGIN_DATE is populated in 2 of 267,386 Oracle rows
 * (0.0007%) and is NULL for every sequence of the reconciliation
 * fixture 105698-00001, whose screen nonetheless shows 04/01/2007 -
 * exactly its AWARD_EFFECTIVE_DATE. Relabelling beginDate would have
 * rendered a blank card. See V078's header for the full evidence.
 *
 * beginDate/closeoutDate are RETAINED on this record even though
 * NEITHER the Summary UI nor the report PDF renders them any more: they
 * are real archived columns and stay available to API consumers.
 * Dropping them from this record would be a breaking API change made
 * for a presentation reason, which is a separate decision from hiding
 * two near-empty cards. begin_date is populated in 2 of 267,386 rows
 * and closeout_date in 1,129.
 *
 * obligationStartDate comes from AWARD_AMOUNT_INFO, not AWARD, and is
 * resolved through Kuali's own current-row rule -
 * MAX(award_amount_info_id), never source_version_number - see
 * docs/kuali-business-rules/Time and Money.md Rule 3 and
 * etl/tests/test_award_amount_info_current_row_selection.py. It is read
 * from the SAME LATERAL subquery that already selects the obligated/
 * anticipated totals, so the amounts and the date on a Summary card can
 * never disagree about which row was "current".
 *
 * alnNumber/alnProgramTitleName are read from one and the same
 * archive.award_cfda row so the number and its title always belong
 * together. alnProgramTitleName is frequently null (cfda_description is
 * populated in 36,779 of 177,317 rows) - that is a known property of
 * the source, NOT a data error, and the UI renders an em dash for it.
 * The KCOEUS CFDA reference table that would otherwise supply the title
 * (CFDA.CFDA_PGM_TTL_NM) has 0 rows, so no fallback lookup exists and
 * the title is deliberately never fabricated from another source.
 *
 * fainId is passed through verbatim. The literal string "unknown" is a
 * real archived Kuali value (it is what 105698-00001 carries) and must
 * reach the UI as "unknown", never coerced to null or an em dash.
 *
 * nsfScienceCode is the RESOLVED NSF_CODES.NSF_CODE value, reached via
 * AWARD.NSF_SEQUENCE_NUMBER; nsfSequenceNumber is carried alongside as
 * audit metadata so the resolution stays re-derivable.
 *
 * Still deliberately absent:
 *   - Federal Award Year: AWARD.FED_AWARD_YEAR exists in Oracle but is
 *     populated in 0 of 267,386 rows - a permanently empty field.
 *   - "account type" as an SAP concept: accountType below is
 *     AWARD.ACCOUNT_TYPE_CODE resolved against ACCOUNT_TYPE, a real
 *     general Award attribute. It is NOT
 *     archive.award_transmission.account_type_code, which is
 *     SAP-transmission-specific and would misrepresent this field.
 */
public record AwardSummaryResponse(
        Long awardId,
        String awardNumber,
        Integer sequenceNumber,
        String title,
        String status,

        // Institution
        String grantNumber,
        String leadUnit,
        String accountType,
        String activityType,
        String awardType,
        String federalClinicalTrial,

        // Sponsor / Federal award
        String sponsor,
        String sponsorCode,
        String sponsorAwardNumber,
        String primeSponsor,
        String primeSponsorCode,
        String primeSponsorAwardId,
        String modificationNumber,
        String fainId,
        String nsfScienceCode,
        Integer nsfSequenceNumber,

        // ALN (number and title always from the same award_cfda row)
        String alnNumber,
        String alnProgramTitleName,

        // Project / dates
        LocalDate awardEffectiveDate,
        LocalDate obligationStartDate,
        LocalDate awardExecutionDate,
        LocalDate beginDate,
        LocalDate closeoutDate,

        // Financial
        BigDecimal obligatedTotalAmount,
        BigDecimal anticipatedTotalAmount,
        String basisOfPaymentCode,
        String basisOfPaymentDescription,
        String methodOfPaymentCode,
        String methodOfPaymentDescription,

        String principalInvestigator,
        String rootAwardNumber,
        String parentAwardNumber,
        boolean primaryCurrent,
        String documentNumber
) {
}
