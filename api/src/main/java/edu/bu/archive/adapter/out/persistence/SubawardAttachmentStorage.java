package edu.bu.archive.adapter.out.persistence;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.util.NoSuchElementException;

import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.S3Exception;

@Component
public class SubawardAttachmentStorage {

    private final S3Client s3;
    private final String documentsBucket;

    public SubawardAttachmentStorage(
            S3Client s3,
            @Value("${ARCHIVE_DOCUMENTS_BUCKET:}")
            String documentsBucket
    ) {
        this.s3 = s3;
        this.documentsBucket = documentsBucket;
    }

    public StoredObject open(SubawardArchivedAttachment attachment) {
        if (documentsBucket.isBlank()) {
            throw new IllegalStateException(
                    "ARCHIVE_DOCUMENTS_BUCKET is not configured"
            );
        }
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

    public record StoredObject(
            InputStream stream,
            long contentLength
    ) {
    }
}
