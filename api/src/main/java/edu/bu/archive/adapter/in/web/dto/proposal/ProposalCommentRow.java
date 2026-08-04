package edu.bu.archive.adapter.in.web.dto.proposal;

import java.time.LocalDateTime;

/*
 * Raw archive.comment_type LEFT JOIN archive.proposal_comment row - one
 * row per comment type code (12/13), with the proposal_comment columns
 * all null when this Proposal family has no comment of that type at
 * all (used by ProposalArchiveV1Service to still render a "No comment
 * recorded" category rather than omitting it). Mirrors AwardCommentRow.
 */
public record ProposalCommentRow(
        Long proposalCommentId,
        Long proposalId,
        Integer sequenceNumber,
        String commentTypeCode,
        String commentTypeDescription,
        String comments,
        LocalDateTime sourceUpdateTimestamp,
        String sourceUpdateUser
) {
}
