package edu.bu.archive.adapter.in.web.dto.attachment;

import java.time.LocalDateTime;

/*
 * Archived File Finder Phase 2 (recordType=ALL): one row from the
 * Award+Proposal UNION ALL query. Identical shape to AttachmentSearchRow
 * (Phase 1's Award-only row) plus recordType, which - unlike the
 * single-domain queries, where the caller/service already knows the
 * domain - must come from the row itself here, since a single result
 * set mixes both. Deliberately a separate record from
 * AttachmentSearchRow rather than adding a field to it: AttachmentSearchRow
 * is constructed positionally in existing Phase 1 tests, and adding a
 * field there would break every one of them for a value Phase 1's own
 * single-domain queries never needed.
 */
public record MixedAttachmentSearchRow(
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
        String uploadStatus,
        boolean downloadable,
        boolean currentVersion
) {
}
