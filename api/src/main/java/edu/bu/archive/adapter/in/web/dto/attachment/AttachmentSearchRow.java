package edu.bu.archive.adapter.in.web.dto.attachment;

import java.time.LocalDateTime;

/*
 * Repository-internal row for Archived File Finder (Phase 1: Award
 * only) - carries the raw archive.attachment_object.upload_status
 * string so AwardArchiveService can derive the human-readable
 * availabilityStatus shown in AttachmentSearchResultResponse, mirroring
 * this codebase's established Row-in-repository /
 * Response-in-service split (e.g. AwardReportTermRow ->
 * AwardReportTermResponse). downloadable is computed here with the
 * exact same SQL expression AwardArchiveRepository.findAttachments and
 * AwardArchiveService.downloadAttachment already use, so a result never
 * claims to be downloadable when the real download endpoint would
 * reject it.
 */
public record AttachmentSearchRow(
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
        String uploadStatus,
        boolean downloadable,
        boolean currentVersion
) {
}
