package edu.bu.researcharchive.api.proposal.dto;

import java.util.List;

public record ProposalWorkspaceResponse(
        ProposalRowResponse proposal,
        List<String> investigators,
        List<String> relatedAwards
) {
}
