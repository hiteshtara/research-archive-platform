package edu.bu.archive.application.negotiation;

import edu.bu.archive.adapter.in.web.dto.negotiation.NegotiationAssociatedRecordResponse;
import edu.bu.archive.adapter.in.web.dto.negotiation.NegotiationAttachmentResponse;
import edu.bu.archive.adapter.in.web.dto.negotiation.NegotiationRowResponse;
import edu.bu.archive.adapter.in.web.dto.negotiation.NegotiationSummaryResponse;
import edu.bu.archive.adapter.in.web.dto.PageResponse;
import edu.bu.archive.adapter.out.persistence.NegotiationArchiveRepository;
import edu.bu.archive.adapter.out.persistence.NegotiationArchivedAttachment;
import edu.bu.archive.adapter.out.persistence.NegotiationAttachmentStorage;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;

import static edu.bu.archive.testsupport.NegotiationFixtures.negotiationRow;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class NegotiationArchiveServiceTest {

    private NegotiationArchiveRepository repository;
    private NegotiationAttachmentStorage attachmentStorage;
    private NegotiationArchiveService service;

    @BeforeEach
    void setUp() {
        repository = mock(NegotiationArchiveRepository.class);
        attachmentStorage = mock(NegotiationAttachmentStorage.class);
        service = new NegotiationArchiveService(
                repository,
                attachmentStorage
        );
    }

    @Test
    void findPageAppliesAwardPaginationBounds() {
        NegotiationSummaryResponse summary = summary();

        when(repository.countNegotiations("award"))
                .thenReturn(205L);
        when(repository.findNegotiations("award", 100, 0))
                .thenReturn(List.of(summary));

        PageResponse<NegotiationSummaryResponse> result = service.findPage(
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

    @Test
    void findAttachmentsRequiresAKnownNegotiation() {
        when(repository.findById(101L))
                .thenReturn(Optional.of(negotiationRow()));
        when(repository.findAttachments(101L))
                .thenReturn(List.of(attachment(1L, 9952L)));

        List<NegotiationAttachmentResponse> result =
                service.findAttachments(101L);

        assertThat(result).hasSize(1);
        verify(repository).findAttachments(101L);
    }

    @Test
    void findAttachmentsRejectsAnUnknownNegotiation() {
        when(repository.findById(999L))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.findAttachments(999L))
                .isInstanceOf(NoSuchElementException.class);
    }

    @Test
    void downloadAttachmentStreamsAnArchivedObject() {
        when(repository.findById(101L))
                .thenReturn(Optional.of(negotiationRow()));
        when(repository.findAttachmentNegotiationId(1L))
                .thenReturn(Optional.of(101L));
        NegotiationArchivedAttachment archived =
                new NegotiationArchivedAttachment(
                        1L, 101L, "notice.pdf", "application/pdf",
                        "bucket", "negotiations/101/1/notice.pdf",
                        123L, "ARCHIVED"
                );
        when(repository.findArchivedAttachment(101L, 1L))
                .thenReturn(Optional.of(archived));
        when(attachmentStorage.open(archived))
                .thenReturn(new NegotiationAttachmentStorage.StoredObject(
                        new ByteArrayInputStream(new byte[]{1, 2, 3}),
                        3L
                ));

        NegotiationAttachmentDownload download =
                service.downloadAttachment(101L, 1L);

        assertThat(download.fileName()).isEqualTo("notice.pdf");
        assertThat(download.mimeType()).isEqualTo("application/pdf");
        assertThat(download.contentLength()).isEqualTo(3L);
    }

    @Test
    void downloadAttachmentRejectsAnAttachmentOwnedByAnotherNegotiation() {
        when(repository.findById(101L))
                .thenReturn(Optional.of(negotiationRow()));
        when(repository.findAttachmentNegotiationId(1L))
                .thenReturn(Optional.of(202L));

        assertThatThrownBy(() -> service.downloadAttachment(101L, 1L))
                .isInstanceOf(NoSuchElementException.class);
    }

    @Test
    void downloadAttachmentRejectsANonArchivedRow() {
        when(repository.findById(101L))
                .thenReturn(Optional.of(negotiationRow()));
        when(repository.findAttachmentNegotiationId(1L))
                .thenReturn(Optional.of(101L));
        when(repository.findArchivedAttachment(101L, 1L))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.downloadAttachment(101L, 1L))
                .isInstanceOf(NoSuchElementException.class);
    }

    @Test
    void findAssociatedRecordResolvesACurrentAward() {
        when(repository.findById(101L))
                .thenReturn(Optional.of(rowWithAssociation(
                        "AWD", "Award", "204107-00001"
                )));
        when(repository.resolveCurrentAwardId("204107-00001"))
                .thenReturn(Optional.of(555L));

        NegotiationAssociatedRecordResponse result =
                service.findAssociatedRecord(101L);

        assertThat(result.kind()).isEqualTo("AWARD");
        assertThat(result.navigableId()).isEqualTo(555L);
        assertThat(result.clickable()).isTrue();
    }

    @Test
    void findAssociatedRecordLeavesAnUnresolvedAwardNonClickable() {
        when(repository.findById(101L))
                .thenReturn(Optional.of(rowWithAssociation(
                        "AWD", "Award", "999999-00001"
                )));
        when(repository.resolveCurrentAwardId("999999-00001"))
                .thenReturn(Optional.empty());

        NegotiationAssociatedRecordResponse result =
                service.findAssociatedRecord(101L);

        assertThat(result.kind()).isEqualTo("AWARD");
        assertThat(result.navigableId()).isNull();
        assertThat(result.clickable()).isFalse();
    }

    @Test
    void findAssociatedRecordResolvesACurrentProposal() {
        when(repository.findById(101L))
                .thenReturn(Optional.of(rowWithAssociation(
                        "IP", "Institutional Proposal", "01164319"
                )));
        when(repository.resolveCurrentProposalId("01164319"))
                .thenReturn(Optional.of(777L));

        NegotiationAssociatedRecordResponse result =
                service.findAssociatedRecord(101L);

        assertThat(result.kind()).isEqualTo("PROPOSAL");
        assertThat(result.navigableId()).isEqualTo(777L);
        assertThat(result.clickable()).isTrue();
    }

    @Test
    void findAssociatedRecordTreatsSubawardIdAsAlreadyInternal() {
        when(repository.findById(101L))
                .thenReturn(Optional.of(rowWithAssociation(
                        "SWD", "Subaward", "1672"
                )));
        when(repository.subawardExists(1672L)).thenReturn(true);

        NegotiationAssociatedRecordResponse result =
                service.findAssociatedRecord(101L);

        assertThat(result.kind()).isEqualTo("SUBAWARD");
        assertThat(result.navigableId()).isEqualTo(1672L);
        assertThat(result.clickable()).isTrue();
    }

    @Test
    void findAssociatedRecordLeavesAMissingSubawardNonClickable() {
        when(repository.findById(101L))
                .thenReturn(Optional.of(rowWithAssociation(
                        "SWD", "Subaward", "999999"
                )));
        when(repository.subawardExists(999999L)).thenReturn(false);

        NegotiationAssociatedRecordResponse result =
                service.findAssociatedRecord(101L);

        assertThat(result.navigableId()).isNull();
        assertThat(result.clickable()).isFalse();
    }

    @Test
    void findAssociatedRecordTreatsNoAssociationAsNeverClickable() {
        when(repository.findById(101L))
                .thenReturn(Optional.of(rowWithAssociation(
                        "NO", "None", "1"
                )));

        NegotiationAssociatedRecordResponse result =
                service.findAssociatedRecord(101L);

        assertThat(result.kind()).isEqualTo("NONE");
        assertThat(result.clickable()).isFalse();
    }

    @Test
    void findAssociatedRecordLeavesAnUnrecognizedTypeVisibleButNotClickable() {
        when(repository.findById(101L))
                .thenReturn(Optional.of(rowWithAssociation(
                        "ZZZ", "Unknown Type", "42"
                )));

        NegotiationAssociatedRecordResponse result =
                service.findAssociatedRecord(101L);

        assertThat(result.associationTypeCode()).isEqualTo("ZZZ");
        assertThat(result.associatedDocumentId()).isEqualTo("42");
        assertThat(result.kind()).isEqualTo("UNSUPPORTED");
        assertThat(result.clickable()).isFalse();
    }

    private NegotiationAttachmentResponse attachment(
            long attachmentId,
            long activityId
    ) {
        return new NegotiationAttachmentResponse(
                attachmentId, activityId, "file.pdf", "application/pdf",
                100L, "ARCHIVED", null, null, true, "N",
                301L, "42001", "Test attachment"
        );
    }

    private NegotiationRowResponse rowWithAssociation(
            String code,
            String description,
            String documentId
    ) {
        NegotiationRowResponse base = negotiationRow();
        return new NegotiationRowResponse(
                base.negotiationId(),
                base.documentNumber(),
                base.negotiationStatusId(),
                base.negotiationStatusCode(),
                base.negotiationStatusDescription(),
                base.negotiationAgreementTypeId(),
                base.negotiationAgreementTypeCode(),
                base.negotiationAgreementTypeDescription(),
                base.negotiationAssociationTypeId(),
                code,
                description,
                base.negotiatorPersonId(),
                base.negotiatorFullName(),
                base.negotiationStartDate(),
                base.negotiationEndDate(),
                base.anticipatedAwardDate(),
                base.documentFolder(),
                documentId,
                base.sourceUpdateTimestamp(),
                base.sourceUpdateUser(),
                base.sourceVersionNumber(),
                base.sourceObjectId(),
                base.documentSourceUpdateTimestamp(),
                base.documentSourceUpdateUser(),
                base.documentSourceVersionNumber(),
                base.documentSourceObjectId()
        );
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

}
