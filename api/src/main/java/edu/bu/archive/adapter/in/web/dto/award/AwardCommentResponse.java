package edu.bu.archive.adapter.in.web.dto.award;

import java.time.LocalDateTime;

/*
 * archive.award_comment - scoped to this specific Award version
 * (a real sequence_number column), distinct from award_notepad below.
 */
public record AwardCommentResponse(
        Long awardCommentId,
        String commentTypeCode,
        String checklistPrintFlag,
        String comments,
        LocalDateTime sourceUpdateTimestamp,
        String sourceUpdateUser
) {
}
