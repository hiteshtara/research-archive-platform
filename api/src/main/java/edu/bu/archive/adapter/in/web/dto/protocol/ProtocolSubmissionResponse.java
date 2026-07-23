package edu.bu.archive.adapter.in.web.dto.protocol;

import java.time.LocalDate;
import java.time.LocalDateTime;

public record ProtocolSubmissionResponse(
        Long submissionId,
        Long protocolId,
        Long sourceProtocolId,
        String protocolNumber,
        Integer sequenceNumber,
        Integer submissionNumber,
        String scheduleId,
        String committeeId,
        String submissionTypeCode,
        String submissionTypeQualCode,
        String submissionStatusCode,
        Long scheduleIdFk,
        Long committeeIdFk,
        String protocolReviewTypeCode,
        LocalDate submissionDate,
        String comments,
        String commDecisionMotionTypeCode,
        Integer yesVoteCount,
        Integer noVoteCount,
        Integer abstainerCount,
        Integer recusedCount,
        String votingComments,
        String isBillable,
        LocalDateTime sourceUpdateTimestamp,
        String sourceUpdateUser,
        Long sourceVersionNumber,
        String sourceObjectId
) {
}
