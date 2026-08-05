package edu.bu.archive.adapter.in.web.dto.explorer;

import java.math.BigDecimal;

/*
 * Backs GET /explorer/proposals - a multi-filter discovery query over
 * the ACTIVE version of every Institutional Proposal family, resolved
 * through archive.proposal_award to its linked Award's CURRENT version
 * and that version's latest archive.award_amount_info snapshot. Mirrors
 * ProposalFundedAwardResponse's own "exact historical ID for audit,
 * resolved current ID for navigation" convention:
 * exactLinkedAwardId is the database relationship's own award_id
 * (audit only, never itself navigated to - it may be a long-superseded
 * version); navigableCurrentAwardId is that Award family's current
 * version, resolved server-side, and is what a client navigates to.
 * obligatedAmount/anticipatedAmount are null whenever the Proposal has
 * no active funded-Award relationship, or that Award has no
 * award_amount_info row yet - never zero-filled.
 */
public record ExplorerProposalDiscoveryResponse(
        long proposalId,
        String proposalNumber,
        String proposalTitle,
        String workflowDocumentNumber,
        int attachmentCount,
        String linkedAwardNumber,
        Long navigableCurrentAwardId,
        String awardTitle,
        BigDecimal obligatedAmount,
        BigDecimal anticipatedAmount,
        Long exactLinkedAwardId
) {
}
