package edu.bu.archive.adapter.in.web.dto.subaward;

import java.time.LocalDateTime;

public record SubawardNotificationResponse(
        Long notificationId,
        Long owningDocumentIdFk,
        String documentNumber,
        String subawardCode,
        Long notificationTypeId,
        String recipients,
        String subject,
        String message,
        LocalDateTime createTimestamp,
        LocalDateTime sourceUpdateTimestamp,
        String sourceUpdateUser,
        Long sourceVersionNumber,
        String sourceObjectId
) {
}
