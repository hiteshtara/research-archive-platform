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
 *
 * awardFundingProposalId/awardId/exactLinkedProposalId come straight
 * from archive.award_funding_proposal itself and are therefore always
 * present, even when the linked Proposal has not been archived into
 * archive.proposal_version yet (see AwardArchiveRepository.findFundingProposalRows -
 * a LEFT JOIN, not an INNER JOIN, to proposal_version). Every other
 * field is Proposal-enrichment and may legitimately be null in that
 * case - the preserved relationship row is the authoritative source of
 * whether a Funding Proposal exists, not whether its detail has been
 * loaded.
 */
public record AwardFundingProposalResponse(
        Long awardFundingProposalId,
        Long awardId,
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
