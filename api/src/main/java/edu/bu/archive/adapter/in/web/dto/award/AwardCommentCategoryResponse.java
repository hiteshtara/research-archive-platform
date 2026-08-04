package edu.bu.archive.adapter.in.web.dto.award;

import java.util.List;

/*
 * One human-readable comment category (e.g. "General Comments"),
 * grouped from every archive.award_comment row across this Award
 * number's whole version family whose comment_type has
 * award_comment_screen_flag='Y' - mirrors Kuali's own Award Comments
 * screen (AwardCommentServiceImpl.retrieveCommentTypes() +
 * retrieveCommentHistoryByType()). "current" is the newest entry in
 * "history" (null when this Award family has no comment of this type
 * at all - the UI renders "No comment recorded" for that case).
 * "history" is newest-to-oldest, with only consecutive
 * exact-text duplicates collapsed - see AwardArchiveService.
 */
public record AwardCommentCategoryResponse(
        String commentTypeCode,
        String commentTypeDescription,
        AwardCommentEntryResponse current,
        List<AwardCommentEntryResponse> history
) {
}
