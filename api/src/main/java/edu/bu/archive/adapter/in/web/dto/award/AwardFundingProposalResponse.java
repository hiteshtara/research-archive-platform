package edu.bu.archive.adapter.in.web.dto.award;

import java.math.BigDecimal;

/*
 * One real archive.award_funding_proposal relationship row (this
 * Award's whole award_number family) - the bidirectional counterpart
 * to ProposalFundedAwardResponse. The database relationship stores an
 * EXACT historical proposalId, which may not be the Proposal family's
 * current ACTIVE version. exactLinkedProposalId is preserved for
 * audit/history; navigableActiveProposalId is that same Proposal
 * family's ACTIVE version (PROPOSAL_SEQUENCE_STATUS = 'ACTIVE' - never
 * assumed from the highest sequence number), resolved server-side, and
 * is what a client navigates to. relationshipActive mirrors
 * AWARD_FUNDING_PROPOSALS.ACTIVE - inactive relationships are still
 * returned, never silently dropped.
 */
public record AwardFundingProposalResponse(
        String proposalNumber,
        String proposalTitle,
        String proposalStatus,
        String workflowDocumentNumber,
        String principalInvestigatorName,
        String sponsorName,
        BigDecimal requestedTotalCost,
        Integer linkedProposalVersion,
        Integer activeProposalVersion,
        boolean relationshipActive,
        Long exactLinkedProposalId,
        Long navigableActiveProposalId
) {
}
