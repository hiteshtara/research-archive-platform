package edu.bu.archive.adapter.in.web.dto.award;

import java.math.BigDecimal;
import java.time.LocalDate;

/*
 * One archive.award_budget_period row for the selected Budget (see
 * AwardBudgetSummaryResponse). Totals are Oracle's own persisted
 * BUDGET_PERIODS values, never recalculated here.
 */
public record AwardBudgetPeriodResponse(
        long budgetPeriodId,
        Integer periodNumber,
        LocalDate startDate,
        LocalDate endDate,
        BigDecimal totalDirectCost,
        BigDecimal totalIndirectCost,
        BigDecimal totalCost
) {
}
