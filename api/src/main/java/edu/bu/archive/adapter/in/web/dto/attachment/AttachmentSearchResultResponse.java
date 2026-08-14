package edu.bu.archive.adapter.in.web.dto.attachment;

import java.time.LocalDateTime;

/*
 * Archived File Finder (Phase 1: Award only) search result. recordType
 * is always "AWARD" today - included now, not added later, so Phase 2
 * (Proposal) is an additive change to this same shape rather than a
 * breaking one. Deliberately never exposes s3Bucket/s3Key/fileDataId/
 * any storage path or credential - downloadable + availabilityStatus
 * are the only signals a client needs to decide whether to show an
 * enabled Download control; the real download endpoint
 * (AwardV1Controller.downloadAttachment, reused as-is, not
 * reimplemented here) re-checks everything server-side regardless of
 * what this response says.
 *
 * availabilityStatus is one of exactly four human-readable values -
 * "Available", "Pending upload", "Source file unavailable", "Failed" -
 * derived deterministically from the real archive.attachment_object
 * .upload_status value (see AwardArchiveService.resolveAvailabilityStatus),
 * never invented or guessed.
 */
public record AttachmentSearchResultResponse(
        String recordType,
        Long parentId,
        String parentNumber,
        String title,
        String principalInvestigator,
        Integer sequenceNumber,
        String workflowDocumentNumber,
        Long attachmentId,
        Long fileId,
        String fileName,
        String documentType,
        LocalDateTime sourceDate,
        Long fileSizeBytes,
        String contentType,
        String availabilityStatus,
        boolean downloadable,
        boolean currentVersion
) {
}
