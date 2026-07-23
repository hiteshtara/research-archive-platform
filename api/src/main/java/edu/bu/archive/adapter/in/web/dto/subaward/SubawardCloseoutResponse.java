package edu.bu.archive.adapter.in.web.dto.subaward;

import java.time.LocalDate;
import java.time.LocalDateTime;

public record SubawardCloseoutResponse(
        Long subawardCloseoutId,
        Long subawardId,
        String subawardCode,
        Integer sequenceNumber,
        Integer closeoutNumber,
        Long closeoutTypeCode,
        LocalDate dateRequested,
        LocalDate dateFollowup,
        LocalDate dateReceived,
        String comments,
        LocalDateTime sourceUpdateTimestamp,
        String sourceUpdateUser,
        Long sourceVersionNumber,
        String sourceObjectId
) {
}
