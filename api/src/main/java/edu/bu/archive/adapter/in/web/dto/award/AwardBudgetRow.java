package edu.bu.archive.adapter.in.web.dto.award;

import java.math.BigDecimal;
import java.time.LocalDate;

/*
 * Raw archive.award_budget row, scoped by the repository to one Award
 * number family bounded to sequences <= the viewed Award's own
 * sequence_number (see docs/kuali-business-rules/Budget.md -
 * budget_version_number is a family-wide monotonic counter, not
 * per-award_id, so this always spans every award_id in the bound, not
 * just the requested one). Used by AwardArchiveService to build both
 * AwardBudgetSummaryResponse (the selected/"current" budget) and
 * AwardBudgetVersionResponse (every budget in scope, with a computed
 * "selected" flag) from the same query.
 */
public record AwardBudgetRow(
        Long budgetId,
        Long owningAwardId,
        Integer owningAwardSequenceNumber,
        Integer budgetVersionNumber,
        String workflowDocumentNumber,
        String statusCode,
        String statusDescription,
        LocalDate startDate,
        LocalDate endDate,
        BigDecimal totalDirectCost,
        BigDecimal totalIndirectCost,
        BigDecimal totalCost
) {
}
