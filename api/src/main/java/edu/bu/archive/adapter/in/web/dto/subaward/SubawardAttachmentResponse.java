package edu.bu.archive.adapter.in.web.dto.subaward;

import java.time.LocalDateTime;

public record SubawardAttachmentResponse(
        Long attachmentId,
        Long subawardId,
        String subawardCode,
        Integer sequenceNumber,
        Long attachmentTypeCode,
        String attachmentTypeDescription,
        Long documentId,
        String fileDataId,
        String fileName,
        String mimeType,
        String documentStatusCode,
        String description,
        LocalDateTime lastUpdateTimestamp,
        String lastUpdateUser,
        LocalDateTime sourceUpdateTimestamp,
        String sourceUpdateUser,
        Long sourceVersionNumber,
        String sourceObjectId
) {
}
