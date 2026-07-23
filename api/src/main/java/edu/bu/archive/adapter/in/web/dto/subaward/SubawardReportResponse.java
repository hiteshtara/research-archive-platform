package edu.bu.archive.adapter.in.web.dto.subaward;

import java.time.LocalDateTime;

public record SubawardReportResponse(
        String subawardReportId,
        Long subawardId,
        String subawardCode,
        Integer sequenceNumber,
        String reportTypeCode,
        String reportTypeDescription,
        LocalDateTime sourceUpdateTimestamp,
        String sourceUpdateUser,
        Long sourceVersionNumber,
        String sourceObjectId
) {
}
