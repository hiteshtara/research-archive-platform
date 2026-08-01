package edu.bu.archive.adapter.in.web.dto.award;

import java.time.LocalDateTime;

/*
 * archive.award_attachment joined to archive.attachment_object.
 * downloadable mirrors the same UPLOADED + non-blank bucket/key check
 * AwardArchiveService.downloadAttachment enforces server-side - a
 * client can use it to decide whether to show a download control at
 * all, without ever seeing the underlying s3Bucket/s3Key themselves.
 */
public record AwardAttachmentResponse(
        Long awardAttachmentId,
        String awardNumber,
        Integer sequenceNumber,
        String fileName,
        String contentType,
        String description,
        String typeCode,
        String documentStatusCode,
        Long fileSizeBytes,
        String uploadStatus,
        boolean downloadable,
        LocalDateTime oracleUpdateTimestamp
) {
}
