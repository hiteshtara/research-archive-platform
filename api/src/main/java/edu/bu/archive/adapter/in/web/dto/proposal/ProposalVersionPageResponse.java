package edu.bu.archive.adapter.in.web.dto.proposal;

import java.util.List;

public record ProposalVersionPageResponse(
        List<ProposalRowResponse> content,
        int page,
        int size,
        long totalElements,
        int totalPages,
        boolean first,
        boolean last
) {
}
