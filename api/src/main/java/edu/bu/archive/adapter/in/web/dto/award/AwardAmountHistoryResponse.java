package edu.bu.archive.adapter.in.web.dto.award;

import java.math.BigDecimal;
import java.time.LocalDate;

/*
 * One award_amount_info row joined back to its own award_version row
 * for effective-date context - award_amount_info itself has no
 * effective-date column (see AwardV1Controller's /amounts Javadoc).
 * Ordered newest-first by sequence_number, same as /versions.
 */
public record AwardAmountHistoryResponse(
        Long awardAmountInfoId,
        Long awardId,
        String awardNumber,
        Integer sequenceNumber,
        BigDecimal obligatedTotalDirect,
        BigDecimal obligatedTotalIndirect,
        BigDecimal obligatedTotalAmount,
        BigDecimal anticipatedChangeDirect,
        BigDecimal anticipatedChangeIndirect,
        BigDecimal anticipatedTotalDirect,
        BigDecimal anticipatedTotalIndirect,
        BigDecimal anticipatedTotalAmount,
        LocalDate awardEffectiveDate,
        String documentNumber,
        Long sourceVersionNumber
) {
}
