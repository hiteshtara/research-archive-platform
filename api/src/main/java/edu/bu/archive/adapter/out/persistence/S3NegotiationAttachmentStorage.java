package edu.bu.archive.adapter.out.persistence;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.NoSuchElementException;

import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.S3Exception;

@Component
@ConditionalOnProperty(
        name = "app.attachments.storage",
        havingValue = "s3",
        matchIfMissing = true
)
public class S3NegotiationAttachmentStorage
        implements NegotiationAttachmentStorage {

    private final S3Client s3;
    private final String documentsBucket;

    public S3NegotiationAttachmentStorage(
            S3Client s3,
            @Value("${ARCHIVE_DOCUMENTS_BUCKET:}")
            String documentsBucket
    ) {
        if (documentsBucket.isBlank()) {
            throw new IllegalStateException(
                    "ARCHIVE_DOCUMENTS_BUCKET is not configured - this "
                            + "bean only activates when "
                            + "app.attachments.storage is unset or 's3' "
                            + "(see S3NegotiationAttachmentStorage), so "
                            + "failing here at startup - rather than on "
                            + "the first download request - is what "
                            + "makes a misconfigured deployment "
                            + "impossible instead of a 500 a user finds "
                            + "by clicking Download."
            );
        }
        this.s3 = s3;
        this.documentsBucket = documentsBucket;
    }

    @Override
    public StoredObject open(NegotiationArchivedAttachment attachment) {
        if (!documentsBucket.equals(attachment.s3Bucket())) {
            throw new NoSuchElementException(
                    "Archived attachment object not found"
            );
        }

        try {
            ResponseInputStream<GetObjectResponse> stream = s3.getObject(
                    GetObjectRequest.builder()
                            .bucket(documentsBucket)
                            .key(attachment.s3Key())
                            .build()
            );
            return new StoredObject(
                    stream,
                    stream.response().contentLength()
            );
        } catch (NoSuchKeyException exception) {
            throw new NoSuchElementException(
                    "Archived attachment object not found",
                    exception
            );
        } catch (S3Exception exception) {
            if (exception.statusCode() == 404) {
                throw new NoSuchElementException(
                        "Archived attachment object not found",
                        exception
                );
            }
            throw exception;
        }
    }
}
