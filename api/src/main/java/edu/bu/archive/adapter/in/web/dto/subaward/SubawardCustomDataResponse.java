package edu.bu.archive.adapter.in.web.dto.subaward;

import java.time.LocalDateTime;

public record SubawardCustomDataResponse(
        Long subawardCustomDataId,
        Long subawardId,
        String subawardCode,
        Integer sequenceNumber,
        Long customAttributeId,
        String value,
        LocalDateTime sourceUpdateTimestamp,
        String sourceUpdateUser,
        Long sourceVersionNumber,
        String sourceObjectId
) {
}
