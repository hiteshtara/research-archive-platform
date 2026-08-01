package edu.bu.archive.adapter.in.web.dto.award;

import java.math.BigDecimal;

/*
 * "Summary card" data for one award_number, batched in a single query
 * across every award_number in a resolved hierarchy - internal to
 * AwardArchiveRepository/AwardArchiveService's tree-building, never
 * returned directly from a controller.
 */
public record AwardSummaryCardRow(
        String awardNumber,
        Long awardId,
        Integer latestSequenceNumber,
        String title,
        String status,
        String principalInvestigator,
        String sponsor,
        String leadUnit,
        BigDecimal currentObligatedAmount
) {
}
