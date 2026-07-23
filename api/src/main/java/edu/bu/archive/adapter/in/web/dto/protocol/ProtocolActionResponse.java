package edu.bu.archive.adapter.in.web.dto.protocol;

import java.time.LocalDateTime;

public record ProtocolActionResponse(
        Long protocolActionId,
        Integer actionId,
        Long protocolId,
        Long sourceProtocolId,
        String protocolNumber,
        Integer sequenceNumber,
        Integer submissionNumber,
        Long submissionIdFk,
        String protocolActionTypeCode,
        String comments,
        String prevSubmissionStatusCode,
        String submissionTypeCode,
        String prevProtocolStatusCode,
        LocalDateTime sourceCreateTimestamp,
        String sourceCreateUser,
        LocalDateTime sourceUpdateTimestamp,
        String sourceUpdateUser,
        LocalDateTime actionDate,
        LocalDateTime actualActionDate,
        Long sourceVersionNumber,
        String sourceObjectId,
        String followupActionCode
) {
}
