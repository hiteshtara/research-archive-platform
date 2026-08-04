package edu.bu.archive.adapter.in.web.dto.proposal;

import java.time.LocalDateTime;

public record ProposalCommentEntryResponse(
        Long proposalCommentId,
        Long proposalId,
        Integer sequenceNumber,
        String comments,
        LocalDateTime sourceUpdateTimestamp,
        String sourceUpdateUser
) {
}
