package edu.bu.archive.adapter.out.persistence;

public record AwardArchivedAttachment(
        long awardAttachmentId,
        long awardId,
        String fileName,
        String contentType,
        String s3Bucket,
        String s3Key,
        Long fileSizeBytes,
        String uploadStatus
) {
}
