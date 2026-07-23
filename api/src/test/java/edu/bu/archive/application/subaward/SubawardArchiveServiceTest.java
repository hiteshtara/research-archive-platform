package edu.bu.archive.application.subaward;

import edu.bu.archive.adapter.in.web.dto.subaward.SubawardPageResponse;
import edu.bu.archive.adapter.in.web.dto.subaward.SubawardRowResponse;
import edu.bu.archive.adapter.in.web.dto.subaward.SubawardSummaryResponse;
import edu.bu.archive.adapter.out.persistence.SubawardArchiveRepository;
import edu.bu.archive.adapter.out.persistence.SubawardArchivedAttachment;
import edu.bu.archive.adapter.out.persistence.SubawardAttachmentStorage;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SubawardArchiveServiceTest {

    private SubawardArchiveRepository repository;
    private SubawardAttachmentStorage attachmentStorage;
    private SubawardArchiveService service;

    @BeforeEach
    void setUp() {
        repository = mock(SubawardArchiveRepository.class);
        attachmentStorage = mock(SubawardAttachmentStorage.class);
        service = new SubawardArchiveService(
                repository,
                attachmentStorage
        );
    }

    @Test
    void findPageAppliesArchivePaginationBounds() {
        SubawardSummaryResponse summary = new SubawardSummaryResponse(
                101L, "1004", 4, "DOC-101", "Title", 1L, "Active",
                "ORG-1", "ACCOUNT-1", null, null, "ACTIVE", null
        );
        when(repository.countSubawards("1004")).thenReturn(205L);
        when(repository.findSubawards("1004", 100, 0))
                .thenReturn(List.of(summary));

        SubawardPageResponse result = service.findPage("1004", -1, 500);

        assertThat(result.content()).containsExactly(summary);
        assertThat(result.page()).isZero();
        assertThat(result.size()).isEqualTo(100);
        assertThat(result.totalElements()).isEqualTo(205L);
        assertThat(result.totalPages()).isEqualTo(3);
        assertThat(result.first()).isTrue();
        assertThat(result.last()).isFalse();
        verify(repository).findSubawards("1004", 100, 0);
    }

    @Test
    void findNotificationsReturnsAnEmptyCollection() {
        when(repository.findById(101L))
                .thenReturn(Optional.of(subawardRow()));
        when(repository.findNotifications(101L)).thenReturn(List.of());

        assertThat(service.findNotifications(101L)).isEmpty();
        verify(repository).findNotifications(101L);
    }

    @Test
    void childEndpointsRejectAnUnknownSubaward() {
        when(repository.findById(999L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.findAmounts(999L))
                .isInstanceOf(NoSuchElementException.class)
                .hasMessage("Subaward not found: 999");
    }

    @Test
    void workspaceRequiresAPositiveSubawardId() {
        assertThatThrownBy(() -> service.findWorkspace(0L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Subaward ID must be positive");
    }

    @Test
    void downloadRejectsAnAttachmentOwnedByAnotherSubaward() {
        when(repository.findById(101L))
                .thenReturn(Optional.of(subawardRow()));
        when(repository.findAttachmentSubawardId(500L))
                .thenReturn(Optional.of(202L));

        assertThatThrownBy(() ->
                service.downloadAttachment(101L, 500L)
        )
                .isInstanceOf(NoSuchElementException.class)
                .hasMessage("Subaward attachment not found");
    }

    @Test
    void downloadRejectsMissingArchivedMetadata() {
        when(repository.findById(101L))
                .thenReturn(Optional.of(subawardRow()));
        when(repository.findAttachmentSubawardId(500L))
                .thenReturn(Optional.of(101L));
        when(repository.findArchivedAttachment(101L, 500L))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() ->
                service.downloadAttachment(101L, 500L)
        )
                .isInstanceOf(NoSuchElementException.class)
                .hasMessage("Archived attachment not found");
    }

    @Test
    void downloadRejectsUnsuccessfulArchiveMetadata() {
        SubawardArchivedAttachment failed =
                new SubawardArchivedAttachment(
                        500L, 101L, "proposal.pdf", "application/pdf",
                        null, null, null, "FAILED"
                );
        when(repository.findById(101L))
                .thenReturn(Optional.of(subawardRow()));
        when(repository.findAttachmentSubawardId(500L))
                .thenReturn(Optional.of(101L));
        when(repository.findArchivedAttachment(101L, 500L))
                .thenReturn(Optional.of(failed));

        assertThatThrownBy(() ->
                service.downloadAttachment(101L, 500L)
        )
                .isInstanceOf(NoSuchElementException.class)
                .hasMessage("Archived attachment not found");
    }

    @Test
    void downloadOpensTheArchivedObjectAfterOwnershipChecks() {
        SubawardArchivedAttachment archived =
                new SubawardArchivedAttachment(
                        500L,
                        101L,
                        "../proposal.pdf",
                        "application/pdf",
                        "configured-bucket",
                        "test/subawards/101/500/proposal.pdf",
                        4L,
                        "ARCHIVED"
                );
        var stream = new java.io.ByteArrayInputStream(
                new byte[]{1, 2, 3, 4}
        );
        when(repository.findById(101L))
                .thenReturn(Optional.of(subawardRow()));
        when(repository.findAttachmentSubawardId(500L))
                .thenReturn(Optional.of(101L));
        when(repository.findArchivedAttachment(101L, 500L))
                .thenReturn(Optional.of(archived));
        when(attachmentStorage.open(archived)).thenReturn(
                new SubawardAttachmentStorage.StoredObject(stream, 4L)
        );

        SubawardAttachmentDownload result =
                service.downloadAttachment(101L, 500L);

        assertThat(result.fileName()).isEqualTo("proposal.pdf");
        assertThat(result.mimeType()).isEqualTo("application/pdf");
        assertThat(result.contentLength()).isEqualTo(4L);
        assertThat(result.stream()).isSameAs(stream);
    }

    private SubawardRowResponse subawardRow() {
        return new SubawardRowResponse(
                101L, "DOC-101", 4, "1004", null, null, null, null,
                null, "Subaward title", null, "Active", null, null, null,
                null, null, null, null, null, null, null, null, null, null,
                null, "ACTIVE", null, null, null, null, null, null, null,
                1L, "OBJECT-1", null, null, 1L, "DOCUMENT-OBJECT-1"
        );
    }
}
