package edu.bu.archive.adapter.in.web.dto.subaward;

import java.time.LocalDate;
import java.time.LocalDateTime;

public record SubawardSummaryResponse(
        Long subawardId,
        String subawardCode,
        Integer sequenceNumber,
        String documentNumber,
        String title,
        Long statusCode,
        String statusDescription,
        String organizationId,
        String accountNumber,
        LocalDate startDate,
        LocalDate endDate,
        String subawardSequenceStatus,
        LocalDateTime sourceUpdateTimestamp
) {
}
