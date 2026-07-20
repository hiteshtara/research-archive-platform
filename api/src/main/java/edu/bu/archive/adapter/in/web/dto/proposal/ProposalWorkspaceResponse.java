package edu.bu.archive.adapter.in.web.dto.proposal;

import java.util.List;

public record ProposalWorkspaceResponse(
        ProposalRowResponse proposal,
        List<String> investigators,
        List<String> relatedAwards
) {
}
