package edu.bu.archive.adapter.in.web.dto.award;

import java.math.BigDecimal;
import java.time.LocalDate;

/*
 * Split scope, proven against real Kuali source and live data (see
 * AwardArchiveRepository.findTimeAndMoneySummary):
 *
 * - awardId/awardNumber/sequenceNumber and every obligated/anticipated
 *   amount are scoped to this EXACT award_id (the specific Award version being
 *   viewed) - genuinely version-specific financial state (the latest
 *   archive.award_amount_info row for this award_id, whether or not
 *   that row itself was Time and Money-created - a plain amendment
 *   copies the running totals forward into the new version's own row).
 * - familyTransactionCount/lastFamilyTimeAndMoneyDocumentNumber/
 *   lastFamilyNoticeDate/lastFamilyTransactionTypeDescription are
 *   scoped to the WHOLE award_number family (every version), matching
 *   TimeAndMoneyActionResponse/TimeAndMoneyHistoryEntryResponse's own
 *   family-wide scope - archive.award_amount_transaction has no
 *   sequence_number column in real Kuali at all, so there is no
 *   version to attribute a "last action" to more narrowly than the
 *   family. Do NOT restrict these four fields back to this exact
 *   award_id: live data across ordinary Awards showed most current
 *   versions have zero Time-and-Money-created rows of their own even
 *   when their family has real history (see the design doc's own
 *   finding that a non-T&M amendment can mint a new "current" version
 *   with nothing but a copy-forward snapshot).
 *
 * Deliberately omits anticipated/obligated "distributable" amounts
 * (ant_distributable_amount/obli_distributable_amount) and an
 * obligated-change direct/indirect split - neither is archived today
 * (the first was explicitly out of scope in the migration that added
 * Time and Money's other columns to award_amount_info; the second does
 * not exist as a stored value in Kuali at all - only anticipated
 * change has a direct/indirect split). See
 * docs/architecture/AWARD_TIME_AND_MONEY_DESIGN.md.
 */
public record TimeAndMoneySummaryResponse(
        long awardId,
        String awardNumber,
        int sequenceNumber,
        BigDecimal obligatedTotalAmount,
        BigDecimal obligatedTotalDirect,
        BigDecimal obligatedTotalIndirect,
        BigDecimal anticipatedTotalAmount,
        BigDecimal anticipatedTotalDirect,
        BigDecimal anticipatedTotalIndirect,
        long familyTransactionCount,
        String lastFamilyTimeAndMoneyDocumentNumber,
        LocalDate lastFamilyNoticeDate,
        String lastFamilyTransactionTypeDescription
) {
}
