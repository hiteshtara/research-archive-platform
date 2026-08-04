package edu.bu.archive.adapter.in.web.dto.award;

import java.math.BigDecimal;
import java.time.LocalDate;

/*
 * The archive-facing "current" Budget for this Award, per
 * docs/kuali-business-rules/Budget.md: the highest budget_version_number
 * (bounded to sequences <= viewedSequenceNumber of this awardNumber
 * family) with Posted status, falling back to the highest
 * budget_version_number that is not Cancelled. This is deliberately
 * NOT Kuali's own live getCurrentBudget() (which targets transient
 * in-progress statuses that almost never survive to a closed archive -
 * see the design doc) - named selectedBudgetId/selectedBudgetVersionNumber
 * throughout this codebase specifically so it is never mistaken for
 * that live concept.
 *
 * All fields are null when this Award family has no Budget in scope at
 * all (or only Cancelled ones) - a real, valid empty state, not an
 * error.
 *
 * totalDirectCost/totalIndirectCost/totalCost are the selected Budget
 * version's own requested amount - a genuinely different concept from
 * awardBudgetTotalCostLimit and budgetChangeTotalCostLimit, both frozen
 * per-version snapshots of an Award-level computation (real Kuali
 * source: Award.getBudgetTotalCostLimit()/
 * AwardBudgetServiceImpl.getTotalCostLimit(), proven live against Award
 * 105698-00002 - see docs/kuali-business-rules/Budget.md). Kuali's own
 * Budget Overview screen renders all three side by side under different
 * labels ("Budget Total Cost Limit", "Budget Change Total Cost Limit",
 * and the version's own Total) - never collapse them into one number.
 */
public record AwardBudgetSummaryResponse(
        long awardId,
        String awardNumber,
        int viewedSequenceNumber,
        Long selectedBudgetId,
        Integer selectedBudgetVersionNumber,
        String statusCode,
        String statusDescription,
        String workflowDocumentNumber,
        LocalDate startDate,
        LocalDate endDate,
        BigDecimal totalDirectCost,
        BigDecimal totalIndirectCost,
        BigDecimal totalCost,
        BigDecimal awardBudgetTotalCostLimit,
        BigDecimal budgetChangeTotalCostLimit
) {
}
