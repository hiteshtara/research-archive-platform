package edu.bu.archive.adapter.in.web.dto.award;

import java.math.BigDecimal;

/*
 * Raw award_person row used only to assemble AwardPersonDetailResponse
 * in AwardArchiveService - see AwardPersonUnitRow/
 * AwardPersonCreditSplitRow/AwardPersonUnitCreditSplitRow for the child
 * rows joined in alongside it.
 */
public record AwardPersonRow(
        Long awardPersonId,
        String personId,
        String fullName,
        String contactRoleCode,
        String keyPersonProjectRole,
        BigDecimal academicYearEffort,
        BigDecimal calendarYearEffort,
        BigDecimal summerEffort,
        BigDecimal totalEffort
) {
}
