package edu.bu.archive.adapter.in.web.dto.proposal;

public record ProposalWorkspaceResponse(
        String proposalNumber,
        ProposalRowResponse current
) {
}
