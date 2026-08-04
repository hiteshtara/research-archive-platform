package edu.bu.archive.adapter.in.web.dto.award;

import java.math.BigDecimal;
import java.time.LocalDate;

/*
 * One archive.award_budget_line_item row for the selected Budget (see
 * AwardBudgetSummaryResponse), spanning every period of that budget.
 * lineItemCost is Oracle's own persisted BUDGET_DETAILS.LINE_ITEM_COST,
 * never recalculated here.
 */
public record AwardBudgetLineItemResponse(
        long budgetLineItemId,
        long budgetPeriodId,
        Integer lineItemNumber,
        String description,
        String costElement,
        LocalDate startDate,
        LocalDate endDate,
        BigDecimal lineItemCost,
        BigDecimal costSharingAmount
) {
}
