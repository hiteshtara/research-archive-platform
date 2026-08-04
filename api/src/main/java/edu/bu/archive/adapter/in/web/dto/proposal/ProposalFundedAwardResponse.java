package edu.bu.archive.adapter.in.web.dto.proposal;

/*
 * Deliberately carries NO internal awardId - Award IDs are never
 * exposed to the UI from this domain; a client resolves the current
 * awardId only at click-time via GET /api/v1/awards/by-number/
 * {awardNumber} (AwardV1Controller.resolveByNumber), immediately
 * before navigating. Resolved family-wide (every proposal_id in this
 * Proposal's whole proposal_number family, matching Kuali's own
 * AllFundingProposalQueryCustomizer business logic - see
 * docs/kuali-business-rules/InstitutionalProposal.md's Award
 * relationship section), then joined to that Award's CURRENT version
 * (is_primary_current) for status.
 */
public record ProposalFundedAwardResponse(
        String awardNumber,
        Integer sequenceNumber,
        String status
) {
}
