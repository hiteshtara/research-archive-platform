package edu.bu.archive.adapter.out.persistence;

import java.io.InputStream;

/**
 * Reads the underlying object for an archived Proposal attachment.
 * Implementations: {@link S3ProposalAttachmentStorage} (production,
 * reads from the private documents S3 bucket) and
 * {@link LocalProposalAttachmentStorage} (local dev, reads synthetic
 * fixtures from disk) - selected via app.attachments.storage, the same
 * property {@link AwardAttachmentStorage}/{@link SubawardAttachmentStorage}
 * use.
 */
public interface ProposalAttachmentStorage {

    StoredObject open(ProposalArchivedAttachment attachment);

    record StoredObject(
            InputStream stream,
            long contentLength
    ) {
    }
}
