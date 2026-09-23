package edu.bu.archive.application.award.report;

import java.time.LocalDateTime;

/**
 * One Award attachment as the consolidated report needs it: the display
 * metadata shown on a manifest row or an information page, plus the S3
 * coordinates used to fetch the bytes server-side.
 *
 * s3Bucket/s3Key are used ONLY to open the object through the existing
 * AwardAttachmentStorage path. They are never rendered into the PDF,
 * never logged, and never returned over the API - see
 * AwardConsolidatedReportAssembler.
 *
 * contentType is carried for display and is deliberately NOT trusted to
 * decide whether a file is a PDF. A large minority of archived rows have
 * repeatedly JSON-escaped values ("application/pdf", "\"application/pdf\"",
 * and deeper) and ~52,000 rows are pure escape-character garbage, so PDF
 * detection sniffs the %PDF- header instead. Tracked as a separate ETL
 * defect; this feature simply does not depend on the column.
 */
public record AwardReportAttachment(
        long awardAttachmentId,
        String fileName,
        String contentType,
        String description,
        String typeCode,
        String documentStatusCode,
        Long fileSizeBytes,
        String uploadStatus,
        String s3Bucket,
        String s3Key,
        LocalDateTime oracleUpdateTimestamp,
        String oracleUpdateUser
) {

    /** True when the archive has a real, fetchable object for this row. */
    public boolean hasArchivedObject() {
        return "UPLOADED".equals(uploadStatus)
                && s3Bucket != null && !s3Bucket.isBlank()
                && s3Key != null && !s3Key.isBlank();
    }
}
