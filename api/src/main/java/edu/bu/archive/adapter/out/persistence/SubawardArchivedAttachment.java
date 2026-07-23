package edu.bu.archive.adapter.out.persistence;

public record SubawardArchivedAttachment(
        long attachmentId,
        long subawardId,
        String originalFileName,
        String mimeType,
        String s3Bucket,
        String s3Key,
        Long byteSize,
        String archiveStatus
) {
}
