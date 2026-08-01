package edu.bu.archive.adapter.in.web.dto.award;

import java.math.BigDecimal;
import java.util.List;

/*
 * Composable /people resource - distinct from the legacy
 * AwardPersonResponse (base award_person columns only). This nests
 * award_person_unit and both credit-split tables per person, per
 * docs/architecture/AWARD_PEOPLE_EXPANSION_DESIGN.md, so a client never
 * has to reassemble raw child rows itself.
 */
public record AwardPersonDetailResponse(
        Long awardPersonId,
        String personId,
        String fullName,
        String contactRoleCode,
        String keyPersonProjectRole,
        boolean leadPrincipalInvestigator,
        BigDecimal academicYearEffort,
        BigDecimal calendarYearEffort,
        BigDecimal summerEffort,
        BigDecimal totalEffort,
        List<AwardPersonUnitResponse> units,
        List<AwardCreditSplitResponse> creditSplits
) {
}
