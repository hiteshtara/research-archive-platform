package edu.bu.archive.adapter.in.web.dto.award;

import java.math.BigDecimal;
import java.time.LocalDate;

/*
 * Scoped to one exact award_id (the specific Award version being
 * viewed), not the whole award_number family - consistent with
 * AwardSummaryResponse. Reflects the latest archive.award_amount_info
 * row for this award_id; lastTimeAndMoneyDocumentNumber/lastNoticeDate/
 * lastTransactionTypeDescription are null when that latest row was
 * never Time and Money-created (the Award's original entry, not yet
 * touched by a Time and Money action).
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
        long timeAndMoneyTransactionCount,
        String lastTimeAndMoneyDocumentNumber,
        LocalDate lastNoticeDate,
        String lastTransactionTypeDescription
) {
}
