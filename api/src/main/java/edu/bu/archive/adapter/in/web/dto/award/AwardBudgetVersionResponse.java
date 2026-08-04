package edu.bu.archive.adapter.in.web.dto.award;

import java.math.BigDecimal;
import java.time.LocalDate;

/*
 * One Budget version within the family-wide, sequence-bounded scope
 * (see docs/kuali-business-rules/Budget.md). owningAwardId/
 * owningAwardSequenceNumber record which specific Award version this
 * particular budget actually belongs to - budget_version_number is a
 * family-wide counter, so consecutive versions routinely belong to
 * different Award sequences (see the real fixture,
 * award_number=103692-00002: versions 1-12 all on award_id 881365,
 * then version 13 jumps to the next sequence). "selected" marks the
 * same budget AwardBudgetSummaryResponse.selectedBudgetId points to -
 * never more than one true per response.
 */
public record AwardBudgetVersionResponse(
        long budgetId,
        int budgetVersionNumber,
        long owningAwardId,
        int owningAwardSequenceNumber,
        String workflowDocumentNumber,
        String statusCode,
        String statusDescription,
        LocalDate startDate,
        LocalDate endDate,
        BigDecimal totalDirectCost,
        BigDecimal totalIndirectCost,
        BigDecimal totalCost,
        BigDecimal awardBudgetTotalCostLimit,
        BigDecimal budgetChangeTotalCostLimit,
        boolean selected
) {
}
