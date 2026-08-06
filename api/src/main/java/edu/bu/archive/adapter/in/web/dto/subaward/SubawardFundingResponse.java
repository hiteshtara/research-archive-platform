package edu.bu.archive.adapter.in.web.dto.subaward;

import java.math.BigDecimal;
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
 *
 * A Subaward can legitimately have MULTIPLE funding rows, each to a
 * DIFFERENT Award family - proven live from Kuali's own business rule
 * (SubAwardDocumentRule.processSaveSubAwardFundingSourceBusinessRules,
 * error.required.subaward.funding.source.award.number.duplicate:
 * "Award {0} has already been added as a Funding Source.") and
 * confirmed in the real archived data (multiple subaward_ids already
 * have 2-3 distinct concurrent Award relationships). There is no
 * primary/current designation among them anywhere in the schema or
 * business rules - every row here is an independent, co-equal
 * relationship, never a "current + history" model.
 *
 * awardAmount mirrors AwardSummaryCardRow's currentObligatedAmount
 * computation exactly (most recent archive.award_amount_info row by
 * source_version_number, for the resolved current Award version) -
 * the same canonical "amount" figure already used elsewhere in this
 * app, not a new one invented for this card.
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
        String awardSponsor,
        BigDecimal awardAmount,
        Long navigableCurrentAwardId,
        boolean archived,
        LocalDateTime sourceUpdateTimestamp,
        String sourceUpdateUser,
        Long sourceVersionNumber,
        String sourceObjectId
) {
}
