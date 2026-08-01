package edu.bu.archive.adapter.in.web.dto.award;

import java.time.LocalDate;
import java.time.LocalDateTime;

/*
 * documentNumber is archive.award_version.modification_number - the
 * real, already-archived per-version document identifier (see
 * AWARD_SEARCH_API_DESIGN.md's "FAIN and document number" section for
 * why this, not a fabricated column, is what "document number" means
 * for one specific Award version).
 */
public record AwardVersionSummaryResponse(
        Long awardId,
        String awardNumber,
        Integer sequenceNumber,
        String status,
        String transactionTypeCode,
        String transactionType,
        LocalDate awardEffectiveDate,
        LocalDateTime updateTimestamp,
        String documentNumber
) {
}
