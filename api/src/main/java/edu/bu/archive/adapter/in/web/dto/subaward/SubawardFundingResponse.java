package edu.bu.archive.adapter.in.web.dto.subaward;

import java.time.LocalDateTime;

public record SubawardFundingResponse(
        Long subawardFundingSourceId,
        Long subawardId,
        String subawardCode,
        Integer sequenceNumber,
        Long awardId,
        LocalDateTime sourceUpdateTimestamp,
        String sourceUpdateUser,
        Long sourceVersionNumber,
        String sourceObjectId
) {
}
