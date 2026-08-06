package edu.bu.archive.adapter.in.web.dto.award;

import java.math.BigDecimal;

/*
 * The bidirectional counterpart to SubawardFundingResponse - one row
 * per real archive.subaward_funding relationship row, family-wide
 * (every award_id in this Award's whole award_number family, matched
 * via the award_number denormalized onto subaward_funding at ETL time
 * - see oracle/subaward/export_subaward_funding.sql). The database
 * relationship stores an EXACT historical subaward_id (a specific
 * physical version), which may not be the Subaward family's current
 * ACTIVE version. exactLinkedSubawardId is preserved for audit/
 * history; navigableCurrentSubawardId is that same Subaward family's
 * ACTIVE version (subaward_sequence_status = 'ACTIVE' - never assumed
 * from the highest sequence number), resolved server-side, and is what
 * a client navigates to. Unlike Award/Proposal's relationship, Subaward
 * funding sources have no active/inactive flag in the source schema -
 * every row here is a real, standing relationship.
 *
 * subawardAmount mirrors the Amounts tab's History of Changes total
 * (see subawardAmountsPresentation.mjs's sumAmendmentTotals): the sum
 * of archive.subaward_amount.obligated_change for the resolved current
 * version - the same figure, computed the same way, not a new one
 * invented for this card.
 */
public record AwardFundingSubawardResponse(
        String subawardCode,
        String organizationId,
        String subawardStatus,
        String workflowDocumentNumber,
        BigDecimal subawardAmount,
        Integer linkedSubawardVersion,
        Integer currentSubawardVersion,
        Long exactLinkedSubawardId,
        Long navigableCurrentSubawardId
) {
}
