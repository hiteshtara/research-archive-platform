package edu.bu.archive.adapter.in.web.dto.award;

import java.time.LocalDate;
import java.time.LocalDateTime;

/*
 * One row per award_id (a specific version), not per award_number -
 * the version-level counterpart to AwardSearchResultResponse (which is
 * always one row per current award_number). Used only by
 * GET /api/v1/awards/versions/search (the Historical Award Records
 * explorer); never scoped to is_primary_current, so both current and
 * historical rows can appear side by side - primaryCurrent is what
 * distinguishes them client-side.
 */
public record AwardVersionSearchResultResponse(
        Long awardId,
        String awardNumber,
        Integer sequenceNumber,
        String documentNumber,
        String title,
        String status,
        String sponsor,
        String principalInvestigator,
        String leadUnit,
        LocalDate awardEffectiveDate,
        LocalDateTime updateTimestamp,
        boolean primaryCurrent
) {
}
