package edu.bu.archive.adapter.out.persistence;

import java.io.InputStream;

/**
 * Reads the underlying object for an archived Award attachment.
 * Implementations: {@link S3AwardAttachmentStorage} (production, reads
 * from the private documents S3 bucket) and
 * {@link LocalAwardAttachmentStorage} (local dev, reads synthetic
 * fixtures from disk) - selected via app.attachments.storage, the same
 * property {@link SubawardAttachmentStorage} uses.
 */
public interface AwardAttachmentStorage {

    StoredObject open(AwardArchivedAttachment attachment);

    record StoredObject(
            InputStream stream,
            long contentLength
    ) {
    }
}
