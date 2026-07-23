package edu.bu.archive.adapter.in.web.dto.negotiation;

import java.time.LocalDateTime;

public record NegotiationNotificationResponse(
        Long notificationId,
        Long notificationTypeId,
        String documentNumber,
        Long owningDocumentIdFk,
        String recipients,
        String subject,
        String message,
        LocalDateTime sourceUpdateTimestamp,
        String sourceUpdateUser,
        Long sourceVersionNumber,
        String sourceObjectId
) {
}
