package edu.bu.archive.adapter.in.web.dto.award;

import java.math.BigDecimal;

/*
 * One archive.award_budget_personnel_detail row (a personnel cost
 * entry on a budget line item) for the selected Budget, left-joined to
 * archive.award_budget_person (the budget-level personnel roster) for
 * name/appointment type. baseSalary is the persisted
 * BUDGET_PERSONNEL_DETAILS.SALARY_REQUESTED value.
 *
 * calculatedSalary is NOT a single Kuali-displayed field - Oracle
 * stores one archive.award_budget_personnel_calculated_amount row per
 * rate application (rate_class_code/rate_type_code combination), not
 * one aggregate "calculated salary". This field is the SUM of every
 * real, persisted calculated_cost row for this personnel line -
 * summing real stored numbers, not computing a new one - see the
 * repository query's own comment before trusting this value across
 * rate types with materially different meanings.
 */
public record AwardBudgetPersonnelResponse(
        long budgetPersonId,
        String personId,
        String fullName,
        String jobCode,
        String appointmentType,
        BigDecimal baseSalary,
        BigDecimal calculatedSalary
) {
}
