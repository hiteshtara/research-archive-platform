package edu.bu.archive.adapter.in.web.dto.proposal;

import java.util.List;

public record ProposalCommentsResponse(
        List<ProposalCommentCategoryResponse> commentCategories
) {
}
