package edu.bu.archive.adapter.out.persistence;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.NoSuchElementException;

/**
 * Local-dev stand-in for {@link S3SubawardAttachmentStorage}: reads
 * synthetic fixture files from disk instead of a private S3 bucket. See
 * local-data/attachments/ (gitignored) and
 * scripts/seed-local-subaward-attachments.sql. Never touches real BU data
 * or AWS - activated only when app.attachments.storage = local.
 */
@Component
@ConditionalOnProperty(
        name = "app.attachments.storage",
        havingValue = "local"
)
public class LocalSubawardAttachmentStorage implements SubawardAttachmentStorage {

    private final Path baseDirectory;
    private final String localBucket;

    public LocalSubawardAttachmentStorage(
            @Value("${app.attachments.local-directory:local-data/attachments}")
            String localDirectory,
            @Value("${app.attachments.local-bucket:local-fixtures}")
            String localBucket
    ) {
        this.baseDirectory = Path.of(localDirectory)
                .toAbsolutePath()
                .normalize();
        this.localBucket = localBucket;
    }

    @Override
    public StoredObject open(SubawardArchivedAttachment attachment) {
        if (!localBucket.equals(attachment.s3Bucket())) {
            throw new NoSuchElementException(
                    "Archived attachment object not found"
            );
        }

        String key = attachment.s3Key();
        if (key == null || key.isBlank()) {
            throw new NoSuchElementException(
                    "Archived attachment object not found"
            );
        }

        // Resolve against the fixture directory and reject anything that
        // escapes it (via "../" or an absolute path in s3Key) before ever
        // touching the filesystem - s3Key ultimately comes from a
        // database row, so this is defense in depth even though only the
        // seed script controls it today.
        Path candidate = baseDirectory.resolve(key).normalize();
        if (!candidate.startsWith(baseDirectory)) {
            throw new NoSuchElementException(
                    "Archived attachment object not found"
            );
        }

        if (!Files.isRegularFile(candidate)) {
            throw new NoSuchElementException(
                    "Archived attachment object not found"
            );
        }

        try {
            InputStream stream = Files.newInputStream(candidate);
            long contentLength = Files.size(candidate);
            return new StoredObject(stream, contentLength);
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }
}
