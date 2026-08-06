package edu.bu.archive.adapter.in.web.dto.negotiation;

import java.time.LocalDateTime;

/*
 * archive.archived_attachment WHERE module_code = 'NEGOTIATION'. Unlike
 * Award/Subaward/Proposal, Negotiation has no domain-specific attachment
 * table - it still uses the original generic V020 destination, so
 * attachmentId is archived_attachment_id (that table's own PK), not a
 * source Oracle attachment ID. activityId comes from
 * source_metadata->>'activity_id' (see repository-negotiation.xml: the
 * attachment collection is nested under NegotiationActivity, not
 * Negotiation itself - proven live 2026-08-06, see
 * docs/architecture/NEGOTIATION_ARCHIVE_COVERAGE.md).
 */
public record NegotiationAttachmentResponse(
        Long attachmentId,
        Long activityId,
        String fileName,
        String contentType,
        Long fileSize,
        String checksum,
        String archiveStatus,
        LocalDateTime sourceUpdateTimestamp,
        String sourceUpdateUser,
        boolean downloadable
) {
}
