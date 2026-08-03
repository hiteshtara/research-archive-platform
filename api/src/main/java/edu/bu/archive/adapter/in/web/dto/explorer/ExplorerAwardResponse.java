package edu.bu.archive.adapter.in.web.dto.explorer;

/*
 * Backs both /explorer/awards (by awardNumber, filtered to the current
 * version) and /explorer/award-versions (by awardId, any specific
 * version) - same shape either way, only the WHERE clause differs (see
 * AwardArchiveRepository.findExplorerAwardByNumber/
 * findExplorerAwardVersionById). Deliberately a separate DTO from
 * AwardRowResponse/AwardVersionSummaryResponse - the Explorer's own
 * contract, kept independent of the public archive API's so either can
 * evolve without the other (see docs/ARCHIVE_EXPLORER.md). title comes
 * directly off archive.award_version (no join needed);
 * principalInvestigator reuses the exact LEFT JOIN LATERAL .../ORDER BY
 * PI-first pattern already proven in
 * AwardArchiveRepository.findSummaryByAwardId - not a new, speculative
 * join.
 */
public record ExplorerAwardResponse(
        Long awardId,
        String awardNumber,
        Integer sequenceNumber,
        String title,
        String status,
        String principalInvestigator,
        String workflowDocumentNumber,
        String modificationNumber,
        String leadUnitNumber,
        String leadUnitName,
        boolean primaryCurrent
) {
}
