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
 *
 * awardBudgetTotalCostLimit/budgetChangeTotalCostLimit are
 * archive.award_budget.obligated_total/total_cost_limit verbatim - real
 * Kuali source (AwardBudgetServiceImpl.setBudgetLimits/getTotalCostLimit)
 * proves these are frozen, per-version snapshots of an Award-level
 * computation taken when the version was created, not the version's own
 * requested amount (totalCost) and not something to recompute here - see
 * docs/kuali-business-rules/Budget.md. Both are null for budget versions
 * created before this snapshot existed (e.g. legacy "Converted Budget
 * Document" versions) - a real archive gap, not a defect.
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
        BigDecimal totalCost,
        BigDecimal awardBudgetTotalCostLimit,
        BigDecimal budgetChangeTotalCostLimit
) {
}
