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
        BigDecimal totalCost
) {
}
