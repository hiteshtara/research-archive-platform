package edu.bu.archive.adapter.in.web.dto.award;

import java.time.LocalDateTime;

/*
 * One archived award_comment row, exposed once its Award version's
 * workflow_document_number has been joined in. Used both as the
 * "current" entry and as a member of "history" on
 * AwardCommentCategoryResponse.
 */
public record AwardCommentEntryResponse(
        Long awardCommentId,
        long awardId,
        Integer sequenceNumber,
        String workflowDocumentNumber,
        String commentText,
        LocalDateTime updateTimestamp,
        String updateUser
) {
}
