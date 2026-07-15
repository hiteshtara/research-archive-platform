package edu.bu.archive.adapter.in.web.dto.workspace;

import java.util.List;

public record IrbWorkspaceResponse(
        IrbProtocolVersionResponse protocol,
        List<IrbFundingResponse> funding,
        List<IrbSubmissionResponse> submissions,
        List<IrbTimelineResponse> timeline
) {
}
