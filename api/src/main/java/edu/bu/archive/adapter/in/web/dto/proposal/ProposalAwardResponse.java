package edu.bu.archive.adapter.in.web.dto.proposal;

public record ProposalAwardResponse(
        Long proposalId,
        Long awardId,
        String awardNumber
) {
}
