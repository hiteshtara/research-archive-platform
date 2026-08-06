package edu.bu.archive.adapter.out.persistence;

public record NegotiationArchivedAttachment(
        long attachmentId,
        long negotiationId,
        String fileName,
        String contentType,
        String s3Bucket,
        String s3Key,
        Long fileSizeBytes,
        String archiveStatus
) {
}
