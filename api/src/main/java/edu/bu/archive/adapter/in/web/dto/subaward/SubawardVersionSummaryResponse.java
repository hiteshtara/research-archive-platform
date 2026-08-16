package edu.bu.archive.adapter.in.web.dto.subaward;

import java.time.LocalDate;
import java.time.LocalDateTime;

/*
 * One archive.subaward row (one historical version) within a
 * subaward_code family - see SubawardArchiveRepository.findVersionSummaries.
 *
 * latestVersion is computed purely structurally (highest sequence_number
 * within the family, subaward_id DESC as a deterministic tiebreaker) -
 * deliberately NOT derived from subawardSequenceStatus ("ACTIVE" et al.).
 * docs/SUBAWARD_DISCOVERY.md explicitly flags SUBAWARD_SEQUENCE_STATUS's
 * exact business semantics as an open, unverified question - relying on
 * it here to mean "is this the current version" would be exactly the
 * kind of unverified scoping assumption CLAUDE.md warns has been
 * implemented backwards before. sequence_number/subaward_id are real,
 * structural, always-populated columns - no open question about what
 * "highest" means.
 */
public record SubawardVersionSummaryResponse(
        Long subawardId,
        String subawardCode,
        Integer sequenceNumber,
        String documentNumber,
        String status,
        LocalDate startDate,
        LocalDate endDate,
        LocalDateTime updateTimestamp,
        boolean latestVersion
) {
}
