package edu.bu.archive.adapter.out.persistence;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.NoSuchElementException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LocalSubawardAttachmentStorageTest {

    @TempDir
    Path tempDir;

    private Path fixturesDirectory;
    private LocalSubawardAttachmentStorage storage;

    @BeforeEach
    void setUp() throws IOException {
        fixturesDirectory = tempDir.resolve("attachments");
        Files.createDirectories(fixturesDirectory);
        storage = new LocalSubawardAttachmentStorage(
                fixturesDirectory.toString(),
                "local-fixtures"
        );
    }

    private SubawardArchivedAttachment archivedAttachment(String s3Key) {
        return new SubawardArchivedAttachment(
                9000000001L,
                1L,
                s3Key,
                "text/plain",
                "local-fixtures",
                s3Key,
                null,
                "ARCHIVED"
        );
    }

    @Test
    void opensAFixtureFileFromTheConfiguredDirectory() throws IOException {
        byte[] content = "hello from a synthetic fixture".getBytes();
        Files.write(fixturesDirectory.resolve("sample-note.txt"), content);

        SubawardAttachmentStorage.StoredObject result =
                storage.open(archivedAttachment("sample-note.txt"));

        assertThat(result.contentLength()).isEqualTo(content.length);
        assertThat(result.stream().readAllBytes()).isEqualTo(content);
    }

    @Test
    void rejectsAMismatchedBucket() throws IOException {
        Files.write(fixturesDirectory.resolve("sample-note.txt"), new byte[]{1});

        SubawardArchivedAttachment attachment = new SubawardArchivedAttachment(
                9000000001L, 1L, "sample-note.txt", "text/plain",
                "some-other-bucket", "sample-note.txt", null, "ARCHIVED"
        );

        assertThatThrownBy(() -> storage.open(attachment))
                .isInstanceOf(NoSuchElementException.class);
    }

    @Test
    void rejectsAMissingFile() {
        assertThatThrownBy(() ->
                storage.open(archivedAttachment("sample-missing.pdf"))
        )
                .isInstanceOf(NoSuchElementException.class);
    }

    @Test
    void rejectsABlankKey() {
        assertThatThrownBy(() -> storage.open(archivedAttachment("")))
                .isInstanceOf(NoSuchElementException.class);
    }

    @Test
    void rejectsPathTraversalOutsideTheFixtureDirectory() throws IOException {
        Path secretOutsideFixtures = tempDir.resolve("secret.txt");
        Files.write(secretOutsideFixtures, "should never be readable".getBytes());

        assertThatThrownBy(() ->
                storage.open(archivedAttachment("../secret.txt"))
        )
                .isInstanceOf(NoSuchElementException.class);
    }

    @Test
    void rejectsAnAbsolutePathEscapingTheFixtureDirectory() throws IOException {
        Path secretOutsideFixtures = tempDir.resolve("secret.txt");
        Files.write(secretOutsideFixtures, "should never be readable".getBytes());

        assertThatThrownBy(() ->
                storage.open(archivedAttachment(secretOutsideFixtures.toString()))
        )
                .isInstanceOf(NoSuchElementException.class);
    }
}
