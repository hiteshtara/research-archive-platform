package edu.bu.archive.application.negotiation;

import edu.bu.archive.adapter.in.web.dto.negotiation.NegotiationPageResponse;
import edu.bu.archive.adapter.in.web.dto.negotiation.NegotiationRowResponse;
import edu.bu.archive.adapter.in.web.dto.negotiation.NegotiationSummaryResponse;
import edu.bu.archive.adapter.out.persistence.NegotiationArchiveRepository;

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

class NegotiationArchiveServiceTest {

    private NegotiationArchiveRepository repository;
    private NegotiationArchiveService service;

    @BeforeEach
    void setUp() {
        repository = mock(NegotiationArchiveRepository.class);
        service = new NegotiationArchiveService(repository);
    }

    @Test
    void findPageAppliesAwardPaginationBounds() {
        NegotiationSummaryResponse summary = summary();

        when(repository.countNegotiations("award"))
                .thenReturn(205L);
        when(repository.findNegotiations("award", 100, 0))
                .thenReturn(List.of(summary));

        NegotiationPageResponse result = service.findPage(
                "award",
                -1,
                500
        );

        assertThat(result.content()).containsExactly(summary);
        assertThat(result.page()).isZero();
        assertThat(result.size()).isEqualTo(100);
        assertThat(result.totalElements()).isEqualTo(205L);
        assertThat(result.totalPages()).isEqualTo(3);
        assertThat(result.first()).isTrue();
        assertThat(result.last()).isFalse();
        verify(repository).findNegotiations("award", 100, 0);
    }

    @Test
    void findNotificationsReturnsAnEmptyCollection() {
        when(repository.findById(101L))
                .thenReturn(Optional.of(negotiationRow()));
        when(repository.findNotifications(101L))
                .thenReturn(List.of());

        assertThat(service.findNotifications(101L)).isEmpty();
        verify(repository).findNotifications(101L);
    }

    @Test
    void childEndpointsRejectAnUnknownNegotiation() {
        when(repository.findById(999L))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.findActivities(999L))
                .isInstanceOf(NoSuchElementException.class)
                .hasMessage("Negotiation not found: 999");
    }

    @Test
    void workspaceRequiresAPositiveNegotiationId() {
        assertThatThrownBy(() -> service.findWorkspace(0L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Negotiation ID must be positive");
    }

    private NegotiationSummaryResponse summary() {
        return new NegotiationSummaryResponse(
                101L,
                "DOC-101",
                1L,
                "ACTIVE",
                "Active",
                2L,
                "AGREEMENT",
                "Agreement",
                3L,
                "AWARD",
                "Award",
                "00001234",
                "PERSON-1",
                "Negotiator",
                null,
                null,
                null
        );
    }

    private NegotiationRowResponse negotiationRow() {
        return new NegotiationRowResponse(
                101L,
                "DOC-101",
                1L,
                "ACTIVE",
                "Active",
                2L,
                "AGREEMENT",
                "Agreement",
                3L,
                "AWARD",
                "Award",
                "PERSON-1",
                "Negotiator",
                null,
                null,
                null,
                null,
                "00001234",
                null,
                null,
                1L,
                "OBJECT-1",
                null,
                null,
                1L,
                "DOCUMENT-OBJECT-1"
        );
    }
}
