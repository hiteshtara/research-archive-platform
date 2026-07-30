package edu.bu.archive.adapter.out.persistence;

import java.io.InputStream;

/**
 * Reads the underlying object for an archived Subaward attachment.
 * Implementations: {@link S3SubawardAttachmentStorage} (production,
 * reads from the private documents S3 bucket) and
 * {@link LocalSubawardAttachmentStorage} (local dev, reads synthetic
 * fixtures from disk) - selected via app.attachments.storage.
 */
public interface SubawardAttachmentStorage {

    StoredObject open(SubawardArchivedAttachment attachment);

    record StoredObject(
            InputStream stream,
            long contentLength
    ) {
    }
}
