package edu.bu.archive.application.protocol;

import edu.bu.archive.adapter.in.web.dto.PageResponse;
import edu.bu.archive.adapter.in.web.dto.protocol.ProtocolSummaryResponse;
import edu.bu.archive.adapter.in.web.dto.protocol.ProtocolVersionResponse;
import edu.bu.archive.adapter.out.persistence.ProtocolArchiveRepository;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ProtocolArchiveServiceTest {

    @Test
    void familySearchPreservesRepositoryPaginationResponse() {
        ProtocolArchiveRepository repository =
                mock(ProtocolArchiveRepository.class);
        ProtocolSummaryResponse family = new ProtocolSummaryResponse(
                "000100",
                3L,
                100L,
                2,
                "Title",
                "Active",
                "Research",
                "Y",
                null
        );
        PageResponse<ProtocolSummaryResponse> expected =
                new PageResponse<>(
                        List.of(family),
                        1,
                        25,
                        51L,
                        3,
                        false,
                        false
                );
        when(repository.findFamilies("researcher", 1, 25))
                .thenReturn(expected);

        PageResponse<ProtocolSummaryResponse> result =
                new ProtocolArchiveService(repository)
                        .findFamilies("researcher", 1, 25);

        assertThat(result).isSameAs(expected);
        assertThat(result.totalElements()).isEqualTo(51L);
        assertThat(result.totalPages()).isEqualTo(3);
        assertThat(result.first()).isFalse();
        assertThat(result.last()).isFalse();
        verify(repository).findFamilies("researcher", 1, 25);
    }

    @Test
    void personnelChecksExactPhysicalParent() {
        ProtocolArchiveRepository repository =
                mock(ProtocolArchiveRepository.class);
        when(repository.findVersion(100L))
                .thenReturn(Optional.of(version()));
        when(repository.findPersonnel(100L)).thenReturn(List.of());

        assertThat(
                new ProtocolArchiveService(repository)
                        .findPersonnel(100L)
        ).isEmpty();

        verify(repository).findVersion(100L);
        verify(repository).findPersonnel(100L);
    }

    @Test
    void fundingChecksExactPhysicalParent() {
        ProtocolArchiveRepository repository =
                mock(ProtocolArchiveRepository.class);
        when(repository.findVersion(100L))
                .thenReturn(Optional.of(version()));
        when(repository.findFunding(100L)).thenReturn(List.of());

        assertThat(
                new ProtocolArchiveService(repository)
                        .findFunding(100L)
        ).isEmpty();

        verify(repository).findVersion(100L);
        verify(repository).findFunding(100L);
    }

    @Test
    void researchAreasCheckExactPhysicalParent() {
        ProtocolArchiveRepository repository =
                mock(ProtocolArchiveRepository.class);
        when(repository.findVersion(100L))
                .thenReturn(Optional.of(version()));
        when(repository.findResearchAreas(100L)).thenReturn(List.of());

        assertThat(
                new ProtocolArchiveService(repository)
                        .findResearchAreas(100L)
        ).isEmpty();

        verify(repository).findVersion(100L);
        verify(repository).findResearchAreas(100L);
    }

    @Test
    void locationsCheckExactPhysicalParent() {
        ProtocolArchiveRepository repository =
                mock(ProtocolArchiveRepository.class);
        when(repository.findVersion(100L))
                .thenReturn(Optional.of(version()));
        when(repository.findLocations(100L)).thenReturn(List.of());

        assertThat(
                new ProtocolArchiveService(repository)
                        .findLocations(100L)
        ).isEmpty();

        verify(repository).findVersion(100L);
        verify(repository).findLocations(100L);
    }

    @Test
    void submissionsCheckExactPhysicalParent() {
        ProtocolArchiveRepository repository =
                mock(ProtocolArchiveRepository.class);
        when(repository.findVersion(100L))
                .thenReturn(Optional.of(version()));
        when(repository.findSubmissions(100L)).thenReturn(List.of());

        assertThat(
                new ProtocolArchiveService(repository)
                        .findSubmissions(100L)
        ).isEmpty();

        verify(repository).findVersion(100L);
        verify(repository).findSubmissions(100L);
    }

    @Test
    void actionsCheckExactPhysicalParent() {
        ProtocolArchiveRepository repository =
                mock(ProtocolArchiveRepository.class);
        when(repository.findVersion(100L))
                .thenReturn(Optional.of(version()));
        when(repository.findActions(100L)).thenReturn(List.of());

        assertThat(
                new ProtocolArchiveService(repository)
                        .findActions(100L)
        ).isEmpty();

        verify(repository).findVersion(100L);
        verify(repository).findActions(100L);
    }

    @Test
    void amendRenewalsCheckExactPhysicalParent() {
        ProtocolArchiveRepository repository =
                mock(ProtocolArchiveRepository.class);
        when(repository.findVersion(100L))
                .thenReturn(Optional.of(version()));
        when(repository.findAmendRenewals(100L)).thenReturn(List.of());

        assertThat(
                new ProtocolArchiveService(repository)
                        .findAmendRenewals(100L)
        ).isEmpty();

        verify(repository).findVersion(100L);
        verify(repository).findAmendRenewals(100L);
    }

    private ProtocolVersionResponse version() {
        return new ProtocolVersionResponse(
                100L,
                "000100",
                2,
                null,
                "Y",
                null,
                null,
                null,
                null,
                "Title",
                null,
                null,
                null,
                null,
                null,
                null,
                null
        );
    }
}
