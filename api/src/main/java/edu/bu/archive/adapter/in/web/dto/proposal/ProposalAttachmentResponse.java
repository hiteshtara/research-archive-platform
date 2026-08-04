package edu.bu.archive.adapter.in.web.dto.proposal;

import java.time.LocalDateTime;

/*
 * downloadable mirrors the exact UPLOADED + non-blank object-key check
 * ProposalArchiveService.downloadAttachment enforces server-side - a
 * client can decide whether to show a download control without ever
 * seeing the underlying s3Bucket/objectKey. attachmentTypeDescription
 * is Oracle's own real PROPOSAL_ATTACHMENT_TYPE taxonomy, denormalized
 * at ETL time - never derived from attachmentTitle (see V062's
 * migration comment: a title containing "Guidelines" is still filed
 * under Oracle's real "Other" type).
 */
public record ProposalAttachmentResponse(
        Long proposalAttachmentId,
        Integer sequenceNumber,
        Integer attachmentNumber,
        String attachmentTitle,
        Integer attachmentTypeCode,
        String attachmentTypeDescription,
        String fileName,
        String contentType,
        String comments,
        Long fileSizeBytes,
        String uploadStatus,
        boolean downloadable,
        LocalDateTime sourceUpdateTimestamp
) {
}
