package edu.bu.archive.application.proposal;

import edu.bu.archive.adapter.in.web.dto.proposal.ProposalRowResponse;
import edu.bu.archive.adapter.in.web.dto.proposal.ProposalVersionPageResponse;
import edu.bu.archive.adapter.in.web.dto.proposal.ProposalWorkspaceResponse;
import edu.bu.archive.adapter.out.persistence.ProposalArchiveRepository;

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

class ProposalArchiveServiceTest {

    private ProposalArchiveRepository repository;
    private ProposalArchiveService service;

    @BeforeEach
    void setUp() {
        repository = mock(ProposalArchiveRepository.class);
        service = new ProposalArchiveService(repository);
    }

    @Test
    void findWorkspaceNormalizesTheProposalNumber() {
        ProposalRowResponse current = proposalRow();

        when(repository.findCurrent("P-100"))
                .thenReturn(Optional.of(current));

        ProposalWorkspaceResponse result =
                service.findWorkspace("  P-100  ");

        assertThat(result.proposalNumber()).isEqualTo("P-100");
        assertThat(result.current()).isEqualTo(current);
    }

    @Test
    void findVersionPageAppliesAwardPaginationBounds() {
        ProposalRowResponse current = proposalRow();

        when(repository.findCurrent("P-100"))
                .thenReturn(Optional.of(current));
        when(repository.countVersions("P-100"))
                .thenReturn(205L);
        when(repository.findVersionRows(
                "P-100",
                100,
                0
        )).thenReturn(List.of(current));

        ProposalVersionPageResponse result =
                service.findVersionPage(
                        "P-100",
                        -1,
                        500
                );

        assertThat(result.page()).isZero();
        assertThat(result.size()).isEqualTo(100);
        assertThat(result.totalElements()).isEqualTo(205L);
        assertThat(result.totalPages()).isEqualTo(3);
        assertThat(result.first()).isTrue();
        assertThat(result.last()).isFalse();

        verify(repository).findVersionRows(
                "P-100",
                100,
                0
        );
    }

    @Test
    void findVersionPageRejectsAnUnknownProposal() {
        when(repository.findCurrent("UNKNOWN"))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() ->
                service.findVersionPage(
                        "UNKNOWN",
                        0,
                        25
                )
        )
                .isInstanceOf(NoSuchElementException.class)
                .hasMessage("Proposal not found: UNKNOWN");
    }

    @Test
    void findWorkspaceRequiresAProposalNumber() {
        assertThatThrownBy(() ->
                service.findWorkspace("   ")
        )
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Proposal number is required");
    }

    private ProposalRowResponse proposalRow() {
        return new ProposalRowResponse(
                10L,
                "P-100",
                3,
                "Proposal title",
                "ACTIVE",
                "New",
                "Research",
                "SP-1",
                "Sponsor",
                "UNIT-1",
                "Unit",
                "PERSON-1",
                "Principal Investigator",
                null,
                null,
                null,
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
