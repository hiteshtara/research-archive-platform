package edu.bu.archive.adapter.out.persistence;

import java.io.InputStream;

/**
 * Reads the underlying object for an archived Negotiation attachment.
 * Implementations: {@link S3NegotiationAttachmentStorage} (production,
 * reads from the private documents S3 bucket) and
 * {@link LocalNegotiationAttachmentStorage} (local dev, reads synthetic
 * fixtures from disk) - selected via app.attachments.storage, the same
 * property {@link AwardAttachmentStorage} uses.
 */
public interface NegotiationAttachmentStorage {

    StoredObject open(NegotiationArchivedAttachment attachment);

    record StoredObject(
            InputStream stream,
            long contentLength
    ) {
    }
}
