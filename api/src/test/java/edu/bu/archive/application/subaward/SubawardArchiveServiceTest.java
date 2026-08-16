package edu.bu.archive.application.subaward;

import edu.bu.archive.adapter.in.web.dto.PageResponse;
import edu.bu.archive.adapter.in.web.dto.subaward.SubawardPageResponse;
import edu.bu.archive.adapter.in.web.dto.subaward.SubawardRowResponse;
import edu.bu.archive.adapter.in.web.dto.subaward.SubawardSummaryResponse;
import edu.bu.archive.adapter.in.web.dto.subaward.SubawardVersionSummaryResponse;
import edu.bu.archive.adapter.out.persistence.SubawardArchiveRepository;
import edu.bu.archive.adapter.out.persistence.SubawardArchivedAttachment;
import edu.bu.archive.adapter.out.persistence.SubawardAttachmentStorage;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;

import static edu.bu.archive.testsupport.SubawardFixtures.subawardRow;
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
    void findVersionsThrowsNotFoundForAMissingSubawardId() {
        when(repository.findById(999L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.findVersions(999L, 0, 25))
                .isInstanceOf(NoSuchElementException.class)
                .hasMessage("Subaward not found: 999");
    }

    @Test
    void findVersionsResolvesTheSubawardCodeThenDelegates() {
        // subawardRow() fixture has subawardId=101, subawardCode="1004".
        when(repository.findById(101L))
                .thenReturn(Optional.of(subawardRow()));
        SubawardVersionSummaryResponse version =
                new SubawardVersionSummaryResponse(
                        101L, "1004", 4, "DOC-101", "Active",
                        null, null, null, true
                );
        when(repository.countVersions("1004")).thenReturn(1L);
        when(repository.findVersionSummaries("1004", 25, 0))
                .thenReturn(List.of(version));

        PageResponse<SubawardVersionSummaryResponse> page =
                service.findVersions(101L, 0, 25);

        assertThat(page.content()).containsExactly(version);
        assertThat(page.totalElements()).isEqualTo(1L);
        verify(repository).countVersions("1004");
        verify(repository).findVersionSummaries("1004", 25, 0);
    }

    @Test
    void findVersionsAppliesTheSamePaginationClampingAsSearch() {
        when(repository.findById(101L))
                .thenReturn(Optional.of(subawardRow()));
        when(repository.countVersions("1004")).thenReturn(0L);
        when(repository.findVersionSummaries("1004", 100, 0))
                .thenReturn(List.of());

        PageResponse<SubawardVersionSummaryResponse> page =
                service.findVersions(101L, -1, 500);

        assertThat(page.page()).isZero();
        assertThat(page.size()).isEqualTo(100);
        verify(repository).findVersionSummaries("1004", 100, 0);
    }

    /*
     * Synthetic 25-version fixture (never real BU/3595 data) matching
     * the real, live-confirmed Subaward Code 3595 population's shape
     * (exactly 25 archive.subaward rows). Proves all 25 are returned,
     * each exactly once (25 distinct subawardIds), in the exact
     * descending-sequence order the repository already produced -
     * service.findVersions is a pure pass-through of content, it must
     * never resort, dedupe, or drop rows.
     */
    @Test
    void findVersionsReturnsAllTwentyFiveVersionsExactlyOnceInDescendingOrder() {
        when(repository.findById(90085L))
                .thenReturn(Optional.of(subawardRow()));
        List<SubawardVersionSummaryResponse> twentyFiveVersions =
                java.util.stream.IntStream.rangeClosed(1, 25)
                        .boxed()
                        .sorted(java.util.Comparator.reverseOrder())
                        .map(sequence -> new SubawardVersionSummaryResponse(
                                90000L + sequence, "1004", sequence,
                                "DOC-" + sequence, "Active", null, null, null,
                                sequence == 25
                        ))
                        .toList();
        when(repository.countVersions("1004")).thenReturn(25L);
        when(repository.findVersionSummaries("1004", 25, 0))
                .thenReturn(twentyFiveVersions);

        PageResponse<SubawardVersionSummaryResponse> page =
                service.findVersions(90085L, 0, 25);

        assertThat(page.content()).hasSize(25);
        assertThat(page.content())
                .extracting(SubawardVersionSummaryResponse::subawardId)
                .doesNotHaveDuplicates();
        assertThat(page.content())
                .extracting(SubawardVersionSummaryResponse::sequenceNumber)
                .isSortedAccordingTo(java.util.Comparator.reverseOrder())
                .containsExactly(
                        25, 24, 23, 22, 21, 20, 19, 18, 17, 16, 15, 14, 13,
                        12, 11, 10, 9, 8, 7, 6, 5, 4, 3, 2, 1
                );
        assertThat(page.totalElements()).isEqualTo(25L);
        assertThat(
                page.content().stream()
                        .filter(SubawardVersionSummaryResponse::latestVersion)
                        .count()
        ).isEqualTo(1L);
    }

    /*
     * Two different Subaward IDs, resolving to two different codes -
     * proves the service resolves and forwards each subawardId's OWN
     * code, never a mixed-up or stale one from a previous call.
     */
    @Test
    void findVersionsNeverMixesUpAnotherCodesResolvedFamily() {
        when(repository.findById(101L))
                .thenReturn(Optional.of(subawardRow()));
        SubawardRowResponse otherCodeRow = new SubawardRowResponse(
                202L, "DOC-202", 1, "9999", null, null, null, null,
                null, "Other title", null, "Active", null, null, null,
                null, null, null, null, null, null, null, null, null, null,
                null, "ACTIVE", null, null, null, null, null, null, null,
                1L, "OBJECT-2", null, null, 1L, "DOCUMENT-OBJECT-2"
        );
        when(repository.findById(202L)).thenReturn(Optional.of(otherCodeRow));
        when(repository.countVersions("1004")).thenReturn(1L);
        when(repository.countVersions("9999")).thenReturn(1L);
        when(repository.findVersionSummaries("1004", 25, 0))
                .thenReturn(List.of());
        when(repository.findVersionSummaries("9999", 25, 0))
                .thenReturn(List.of());

        service.findVersions(101L, 0, 25);
        service.findVersions(202L, 0, 25);

        verify(repository).findVersionSummaries("1004", 25, 0);
        verify(repository).findVersionSummaries("9999", 25, 0);
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

}
