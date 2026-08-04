package edu.bu.archive.adapter.out.persistence;

public record ProposalArchivedAttachment(
        long proposalAttachmentId,
        long proposalId,
        String fileName,
        String contentType,
        String s3Bucket,
        String s3Key,
        Long fileSizeBytes,
        String uploadStatus
) {
}
