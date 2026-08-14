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
 *
 * restrictedFlag is the legacy KCOEUS.NEGOTIATION_ATTACHMENT.RESTRICTED
 * value ('Y'/'N', or null for attachments predating that column's
 * capture) - preserved verbatim from
 * archive.archived_attachment.legacy_restricted_flag (V076). It is
 * informational only: it never gates listing, viewing, or downloading -
 * see docs/architecture/NEGOTIATION_ATTACHMENT_ACCESS_DESIGN.md for the
 * product decision. downloadable already reflects the real, separate
 * reason a file may be unavailable (no archived S3 object - most
 * commonly because the source Oracle BLOB was never captured at all,
 * per that same design doc's Oracle-side reconciliation).
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
        boolean downloadable,
        String restrictedFlag
) {
}
