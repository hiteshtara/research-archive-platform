package edu.bu.archive.adapter.in.web.dto.subaward;

import java.time.LocalDateTime;

/*
 * exactLinkedAwardId is the raw, authoritative
 * SUBAWARD_FUNDING_SOURCE.AWARD_ID - preserved for audit even when
 * navigableCurrentAwardId can't be resolved. It is deliberately never
 * assumed to be the current Award version (see
 * SubawardArchiveRepository.findFunding): navigableCurrentAwardId is
 * resolved separately, by award_number, against
 * archive.award_version.is_primary_current. archived mirrors whether
 * that resolution succeeded, i.e. whether navigableCurrentAwardId is
 * non-null - the same "clickable" semantics
 * NegotiationAssociatedRecordResponse already established for Award/
 * Proposal links.
 */
public record SubawardFundingResponse(
        Long subawardFundingSourceId,
        Long subawardId,
        String subawardCode,
        Integer sequenceNumber,
        Long exactLinkedAwardId,
        String awardNumber,
        String awardTitle,
        String awardStatus,
        Long navigableCurrentAwardId,
        boolean archived,
        LocalDateTime sourceUpdateTimestamp,
        String sourceUpdateUser,
        Long sourceVersionNumber,
        String sourceObjectId
) {
}
