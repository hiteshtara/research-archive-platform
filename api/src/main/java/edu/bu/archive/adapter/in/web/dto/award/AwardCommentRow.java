package edu.bu.archive.adapter.in.web.dto.award;

import java.time.LocalDateTime;

/*
 * Raw archive.comment_type LEFT JOIN archive.award_comment row - one row
 * per comment type screen_flag='Y' reference row, with the award_comment/
 * award_version columns all null when this Award family has no comment
 * of that type at all (used by AwardArchiveService to still render a
 * "No comment recorded" category, not just omit it). See
 * AwardArchiveRepository.findComments.
 */
public record AwardCommentRow(
        Long awardCommentId,
        Long awardId,
        Integer sequenceNumber,
        String workflowDocumentNumber,
        String commentTypeCode,
        String commentTypeDescription,
        String comments,
        LocalDateTime sourceUpdateTimestamp,
        String sourceUpdateUser
) {
}
