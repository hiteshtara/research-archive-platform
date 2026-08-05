package edu.bu.archive.adapter.in.web.dto.proposal;

/*
 * One real archive.proposal_award relationship row (this Proposal's
 * whole proposalNumber family, matching Kuali's own
 * AllFundingProposalQueryCustomizer business logic - see
 * docs/kuali-business-rules/InstitutionalProposal.md). The database
 * relationship stores an EXACT historical awardId - often years-old
 * and long since superseded (real fixture: family 205 links award_id
 * 148155, award_number 200268-00001's sequence 1 of 5,
 * is_primary_current = FALSE). exactLinkedAwardId is preserved for
 * audit/history; navigableCurrentAwardId is that same Award family's
 * CURRENT version, resolved server-side, and is what a client
 * navigates to - so the UI never has to guess or make a second
 * resolve-by-number call. relationshipActive mirrors
 * AWARD_FUNDING_PROPOSALS.ACTIVE - inactive relationships are still
 * returned, never silently dropped, so the UI can label them rather
 * than hide them.
 */
public record ProposalFundedAwardResponse(
        String awardNumber,
        String awardTitle,
        String awardStatus,
        Integer proposalVersion,
        Integer linkedAwardVersion,
        Integer currentAwardVersion,
        boolean relationshipActive,
        Long exactLinkedAwardId,
        Long navigableCurrentAwardId
) {
}
