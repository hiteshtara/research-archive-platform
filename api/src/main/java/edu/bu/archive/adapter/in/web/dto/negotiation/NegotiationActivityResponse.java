package edu.bu.archive.adapter.in.web.dto.negotiation;

import java.time.LocalDate;
import java.time.LocalDateTime;

public record NegotiationActivityResponse(
        Long negotiationActivityId,
        Long negotiationId,
        Long activityTypeId,
        String activityTypeCode,
        String activityTypeDescription,
        Long locationId,
        String locationCode,
        String locationDescription,
        LocalDate startDate,
        LocalDate endDate,
        LocalDate createDate,
        LocalDate followupDate,
        String lastModifiedUser,
        LocalDate lastModifiedDate,
        String description,
        String restricted,
        LocalDateTime sourceUpdateTimestamp,
        String sourceUpdateUser,
        Long sourceVersionNumber,
        String sourceObjectId
) {
}
