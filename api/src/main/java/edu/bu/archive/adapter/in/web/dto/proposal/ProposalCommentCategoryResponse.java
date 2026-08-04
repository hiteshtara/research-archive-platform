package edu.bu.archive.adapter.in.web.dto.proposal;

import java.util.List;

/*
 * One human-readable comment category, grouped from every real
 * archive.proposal_comment row across this Proposal number's whole
 * version family - mirrors AwardCommentCategoryResponse's own history
 * behavior (newest-to-oldest, "current" is the newest entry). Reuses
 * the shared archive.comment_type table. Only codes 12 ("Proposal
 * Comments") and 13 ("Proposal IP Review Comments") are shown, per
 * explicit instruction - archive.comment_type has no institutional-
 * proposal-equivalent of award_comment_screen_flag to data-drive this
 * (see V063's migration comment), so the two categories are selected
 * explicitly rather than via a reusable flag column that does not
 * exist for this domain.
 */
public record ProposalCommentCategoryResponse(
        String commentTypeCode,
        String commentTypeDescription,
        ProposalCommentEntryResponse current,
        List<ProposalCommentEntryResponse> history
) {
}
