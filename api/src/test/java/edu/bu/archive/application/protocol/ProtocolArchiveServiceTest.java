package edu.bu.archive.application.protocol;

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
