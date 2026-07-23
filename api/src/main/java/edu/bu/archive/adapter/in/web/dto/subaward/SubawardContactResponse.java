package edu.bu.archive.adapter.in.web.dto.subaward;

import java.time.LocalDateTime;

public record SubawardContactResponse(
        Long subawardContactId,
        Long subawardId,
        String subawardCode,
        Integer sequenceNumber,
        String contactTypeCode,
        Long rolodexId,
        String requisitionerId,
        LocalDateTime sourceUpdateTimestamp,
        String sourceUpdateUser,
        Long sourceVersionNumber,
        String sourceObjectId
) {
}
