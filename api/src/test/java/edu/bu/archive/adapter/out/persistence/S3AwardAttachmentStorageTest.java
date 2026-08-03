package edu.bu.archive.adapter.out.persistence;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.S3Exception;

import java.util.NoSuchElementException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class S3AwardAttachmentStorageTest {

    private S3Client s3;

    @BeforeEach
    void setUp() {
        s3 = mock(S3Client.class);
    }

    private AwardArchivedAttachment archivedAttachment(String bucket) {
        return new AwardArchivedAttachment(
                500L, 101L, "budget-justification.pdf",
                "application/pdf", bucket,
                "test/awards/101/500/budget-justification.pdf", 4L,
                "UPLOADED"
        );
    }

    @Test
    void throwsAtConstructionWhenTheDocumentsBucketIsNotConfigured() {
        // Fails at bean construction (application startup), not on the
        // first download request - a misconfigured deployment should
        // never start successfully in the first place.
        assertThatThrownBy(() -> new S3AwardAttachmentStorage(s3, ""))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void rejectsAnAttachmentFromADifferentBucket() {
        S3AwardAttachmentStorage storage =
                new S3AwardAttachmentStorage(s3, "configured-bucket");

        assertThatThrownBy(() ->
                storage.open(archivedAttachment("some-other-bucket"))
        )
                .isInstanceOf(NoSuchElementException.class);
    }

    @Test
    void mapsNoSuchKeyToNoSuchElement() {
        S3AwardAttachmentStorage storage =
                new S3AwardAttachmentStorage(s3, "configured-bucket");
        when(s3.getObject(any(GetObjectRequest.class)))
                .thenThrow(NoSuchKeyException.builder().build());

        assertThatThrownBy(() ->
                storage.open(archivedAttachment("configured-bucket"))
        )
                .isInstanceOf(NoSuchElementException.class);
    }

    @Test
    void mapsA404S3ExceptionToNoSuchElement() {
        S3AwardAttachmentStorage storage =
                new S3AwardAttachmentStorage(s3, "configured-bucket");
        S3Exception notFound =
                (S3Exception) S3Exception.builder().statusCode(404).build();
        when(s3.getObject(any(GetObjectRequest.class)))
                .thenThrow(notFound);

        assertThatThrownBy(() ->
                storage.open(archivedAttachment("configured-bucket"))
        )
                .isInstanceOf(NoSuchElementException.class);
    }

    @Test
    void rethrowsANon404S3Exception() {
        S3AwardAttachmentStorage storage =
                new S3AwardAttachmentStorage(s3, "configured-bucket");
        S3Exception serverError =
                (S3Exception) S3Exception.builder().statusCode(500).build();
        when(s3.getObject(any(GetObjectRequest.class)))
                .thenThrow(serverError);

        assertThatThrownBy(() ->
                storage.open(archivedAttachment("configured-bucket"))
        )
                .isSameAs(serverError);
    }

    @Test
    void opensTheObjectWhenTheBucketMatches() {
        S3AwardAttachmentStorage storage =
                new S3AwardAttachmentStorage(s3, "configured-bucket");
        byte[] content = {1, 2, 3, 4};
        GetObjectResponse response = GetObjectResponse.builder()
                .contentLength((long) content.length)
                .build();
        ResponseInputStream<GetObjectResponse> responseStream =
                new ResponseInputStream<>(
                        response,
                        new java.io.ByteArrayInputStream(content)
                );
        when(s3.getObject(any(GetObjectRequest.class)))
                .thenReturn(responseStream);

        AwardAttachmentStorage.StoredObject result =
                storage.open(archivedAttachment("configured-bucket"));

        assertThat(result.contentLength()).isEqualTo(4L);
    }
}
