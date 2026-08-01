package edu.bu.archive.application.award;

import edu.bu.archive.adapter.in.web.dto.PageResponse;
import edu.bu.archive.adapter.in.web.dto.award.AwardAmountHistoryResponse;
import edu.bu.archive.adapter.in.web.dto.award.AwardAttachmentResponse;
import edu.bu.archive.adapter.in.web.dto.award.AwardCommentResponse;
import edu.bu.archive.adapter.in.web.dto.award.AwardCommentsResponse;
import edu.bu.archive.adapter.in.web.dto.award.AwardNotepadEntryResponse;
import edu.bu.archive.adapter.in.web.dto.award.AwardPersonCreditSplitRow;
import edu.bu.archive.adapter.in.web.dto.award.AwardPersonDetailResponse;
import edu.bu.archive.adapter.in.web.dto.award.AwardPersonRow;
import edu.bu.archive.adapter.in.web.dto.award.AwardPersonUnitCreditSplitRow;
import edu.bu.archive.adapter.in.web.dto.award.AwardPersonUnitRow;
import edu.bu.archive.adapter.in.web.dto.award.AwardReportTermRecipientRow;
import edu.bu.archive.adapter.in.web.dto.award.AwardReportTermRow;
import edu.bu.archive.adapter.in.web.dto.award.AwardSapTransmissionChildRow;
import edu.bu.archive.adapter.in.web.dto.award.AwardSapTransmissionResponse;
import edu.bu.archive.adapter.in.web.dto.award.AwardSapTransmissionRow;
import edu.bu.archive.adapter.in.web.dto.award.AwardSponsorTermResponse;
import edu.bu.archive.adapter.in.web.dto.award.AwardTermsResponse;
import edu.bu.archive.adapter.out.persistence.AwardArchivedAttachment;
import edu.bu.archive.adapter.out.persistence.AwardArchiveRepository;
import edu.bu.archive.adapter.out.persistence.AwardAttachmentStorage;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/*
 * Assembly-logic tests for the 6 composable Award sections (People and
 * Units, Amounts, Terms, Comments and Notepad, SAP Transmission
 * History, Attachments) - the Java-side grouping AwardArchiveService
 * performs on top of AwardArchiveRepository's flat/row-level queries.
 * See AwardV1ControllerTest for HTTP-layer routing and
 * AwardV1ContractTest for response-shape stability.
 */
class AwardArchiveServiceCompositeSectionsTest {

    private AwardArchiveRepository repository;
    private AwardAttachmentStorage attachmentStorage;
    private AwardArchiveService service;

    @BeforeEach
    void setUp() {
        repository = mock(AwardArchiveRepository.class);
        attachmentStorage = mock(AwardAttachmentStorage.class);
        service = new AwardArchiveService(repository, attachmentStorage);
        when(repository.findAwardNumberForId(3L))
                .thenReturn(Optional.of("100004-00003"));
    }

    // --- People and Units ------------------------------------------------

    @Test
    void findPeopleGroupsUnitsAndCreditSplitsUnderTheirOwnPerson() {
        AwardPersonRow pi = new AwardPersonRow(
                10L, "P100", "MICHAEL MCCLEAN", "PI", "PI",
                BigDecimal.ONE, BigDecimal.ONE, null, BigDecimal.ONE
        );
        AwardPersonRow coInvestigator = new AwardPersonRow(
                20L, "P200", "JANE DOE", "COI", "Co-Investigator",
                null, null, null, null
        );
        when(repository.findPersonRows(3L))
                .thenReturn(List.of(pi, coInvestigator));
        when(repository.findPersonUnitRows(3L)).thenReturn(List.of(
                new AwardPersonUnitRow(100L, 10L, "SPH-ENV", "Y")
        ));
        when(repository.findPersonCreditSplitRows(3L)).thenReturn(List.of(
                new AwardPersonCreditSplitRow(10L, "PROJECT", new BigDecimal("50.00"))
        ));
        when(repository.findPersonUnitCreditSplitRows(3L)).thenReturn(List.of(
                new AwardPersonUnitCreditSplitRow(100L, "OVERHEAD", new BigDecimal("30.00"))
        ));

        List<AwardPersonDetailResponse> people = service.findPeople(3L);

        assertThat(people).hasSize(2);
        AwardPersonDetailResponse piResponse = people.get(0);
        assertThat(piResponse.leadPrincipalInvestigator()).isTrue();
        assertThat(piResponse.units()).hasSize(1);
        assertThat(piResponse.units().get(0).unitNumber()).isEqualTo("SPH-ENV");
        assertThat(piResponse.units().get(0).leadUnit()).isTrue();
        assertThat(piResponse.units().get(0).creditSplits())
                .extracting("creditTypeCode")
                .containsExactly("OVERHEAD");
        assertThat(piResponse.creditSplits())
                .extracting("creditTypeCode")
                .containsExactly("PROJECT");

        AwardPersonDetailResponse coResponse = people.get(1);
        assertThat(coResponse.leadPrincipalInvestigator()).isFalse();
        assertThat(coResponse.units()).isEmpty();
        assertThat(coResponse.creditSplits()).isEmpty();
    }

    @Test
    void findPeopleThrowsNotFoundForAnUnknownAwardId() {
        when(repository.findAwardNumberForId(999L))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.findPeople(999L))
                .isInstanceOf(NoSuchElementException.class);
    }

    @Test
    void findPeopleReturnsAnEmptyListWhenThereAreNoPersonRows() {
        when(repository.findPersonRows(3L)).thenReturn(List.of());
        when(repository.findPersonUnitRows(3L)).thenReturn(List.of());
        when(repository.findPersonCreditSplitRows(3L)).thenReturn(List.of());
        when(repository.findPersonUnitCreditSplitRows(3L)).thenReturn(List.of());

        assertThat(service.findPeople(3L)).isEmpty();
    }

    // --- Amounts -----------------------------------------------------

    @Test
    void findAmountsBuildsAPaginatedNewestFirstHistory() {
        AwardAmountHistoryResponse row = new AwardAmountHistoryResponse(
                1L, 3L, "100004-00003", 1,
                BigDecimal.TEN, BigDecimal.ONE, BigDecimal.TEN,
                null, null, null, null, BigDecimal.TEN,
                LocalDate.of(2020, 1, 1), "DOC-1", 1L
        );
        when(repository.countAmountHistory("100004-00003")).thenReturn(1L);
        when(repository.findAmountHistory("100004-00003", 50, 0))
                .thenReturn(List.of(row));

        PageResponse<AwardAmountHistoryResponse> page =
                service.findAmounts(3L, 0, 50);

        assertThat(page.content()).containsExactly(row);
        assertThat(page.totalElements()).isEqualTo(1L);
    }

    @Test
    void findAmountsReturnsAnEmptyPageWhenThereIsNoAmountHistory() {
        when(repository.countAmountHistory("100004-00003")).thenReturn(0L);
        when(repository.findAmountHistory("100004-00003", 50, 0))
                .thenReturn(List.of());

        PageResponse<AwardAmountHistoryResponse> page =
                service.findAmounts(3L, 0, 50);

        assertThat(page.content()).isEmpty();
        assertThat(page.totalElements()).isZero();
        assertThat(page.totalPages()).isZero();
    }

    // --- Terms ---------------------------------------------------------

    @Test
    void findTermsGroupsRecipientsUnderTheirOwnReportTerm() {
        AwardSponsorTermResponse sponsorTerm =
                new AwardSponsorTermResponse(1L, 555L);
        when(repository.findSponsorTerms(3L))
                .thenReturn(List.of(sponsorTerm));
        when(repository.findReportTermRows(3L)).thenReturn(List.of(
                new AwardReportTermRow(
                        200L, "FINANCIAL", "FIN-1", "ANNUAL",
                        "ANNIVERSARY", "PI", LocalDate.of(2021, 6, 30)
                )
        ));
        when(repository.findReportTermRecipientRows(3L)).thenReturn(List.of(
                new AwardReportTermRecipientRow(
                        900L, 200L, 42L, "PI", null, 1
                )
        ));

        AwardTermsResponse terms = service.findTerms(3L);

        assertThat(terms.sponsorTerms()).containsExactly(sponsorTerm);
        assertThat(terms.reportTerms()).hasSize(1);
        assertThat(terms.reportTerms().get(0).recipients()).hasSize(1);
        assertThat(terms.reportTerms().get(0).recipients().get(0).contactId())
                .isEqualTo(42L);
    }

    @Test
    void findTermsReturnsEmptyGroupsWhenThereAreNoTermRows() {
        when(repository.findSponsorTerms(3L)).thenReturn(List.of());
        when(repository.findReportTermRows(3L)).thenReturn(List.of());
        when(repository.findReportTermRecipientRows(3L)).thenReturn(List.of());

        AwardTermsResponse terms = service.findTerms(3L);

        assertThat(terms.sponsorTerms()).isEmpty();
        assertThat(terms.reportTerms()).isEmpty();
    }

    // --- Comments and Notepad --------------------------------------------

    @Test
    void findCommentsKeepsCommentsAndNotepadAsSeparateGroups() {
        AwardCommentResponse comment = new AwardCommentResponse(
                1L, "GENERAL", "N", "A version-scoped comment.",
                LocalDateTime.of(2021, 1, 1, 0, 0), "jsmith"
        );
        AwardNotepadEntryResponse notepadEntry = new AwardNotepadEntryResponse(
                1L, 1, "Kickoff", "A family-wide note.\nSecond line.",
                "N", LocalDateTime.of(2020, 1, 1, 0, 0), "jsmith",
                LocalDateTime.of(2020, 1, 1, 0, 0), "jsmith"
        );
        when(repository.findComments(3L)).thenReturn(List.of(comment));
        when(repository.findNotepadEntries("100004-00003"))
                .thenReturn(List.of(notepadEntry));

        AwardCommentsResponse comments = service.findComments(3L);

        assertThat(comments.comments()).containsExactly(comment);
        assertThat(comments.notepadEntries()).containsExactly(notepadEntry);
    }

    @Test
    void findCommentsReturnsEmptyListsRatherThanNull() {
        when(repository.findComments(3L)).thenReturn(List.of());
        when(repository.findNotepadEntries("100004-00003"))
                .thenReturn(List.of());

        AwardCommentsResponse comments = service.findComments(3L);

        assertThat(comments.comments()).isEmpty();
        assertThat(comments.notepadEntries()).isEmpty();
    }

    // --- SAP Transmission History -----------------------------------

    @Test
    void findSapTransmissionsGroupsChildrenUnderTheirOwnTransmission() {
        AwardSapTransmissionRow transmission = new AwardSapTransmissionRow(
                700L, "100004-00003", 1, "jsmith", "SAP-GW", "Y",
                LocalDate.of(2021, 3, 1), "1", 28, "NIH", "28",
                "DOC-1", "<xml>sent</xml>", "<xml>returned</xml>"
        );
        when(repository.countTransmissions(3L)).thenReturn(1L);
        when(repository.findTransmissionRows(3L, 25, 0))
                .thenReturn(List.of(transmission));
        when(repository.findTransmissionChildRows(List.of(700L)))
                .thenReturn(List.of(
                        new AwardSapTransmissionChildRow(
                                800L, 700L, "100004-00099", 1,
                                "DOC-1", "DOC-2", "SPH", "SUB",
                                "OH1", "B1", "N"
                        )
                ));

        PageResponse<AwardSapTransmissionResponse> page =
                service.findSapTransmissions(3L, 0, 25);

        assertThat(page.content()).hasSize(1);
        AwardSapTransmissionResponse result = page.content().get(0);
        assertThat(result.successful()).isTrue();
        assertThat(result.children()).hasSize(1);
        assertThat(result.children().get(0).childDocumentNumber())
                .isEqualTo("DOC-2");
    }

    @Test
    void findSapTransmissionsTreatsANonAffirmativeIndicatorAsUnsuccessful() {
        AwardSapTransmissionRow failed = new AwardSapTransmissionRow(
                701L, "100004-00003", 1, "jsmith", "SAP-GW", "N",
                LocalDate.of(2021, 3, 2), "1", 28, "NIH", "28",
                "DOC-2", null, null
        );
        when(repository.countTransmissions(3L)).thenReturn(1L);
        when(repository.findTransmissionRows(3L, 25, 0))
                .thenReturn(List.of(failed));
        when(repository.findTransmissionChildRows(List.of(701L)))
                .thenReturn(List.of());

        PageResponse<AwardSapTransmissionResponse> page =
                service.findSapTransmissions(3L, 0, 25);

        assertThat(page.content().get(0).successful()).isFalse();
        assertThat(page.content().get(0).children()).isEmpty();
    }

    @Test
    void findSapTransmissionsNeverQueriesChildrenWhenThePageIsEmpty() {
        when(repository.countTransmissions(3L)).thenReturn(0L);
        when(repository.findTransmissionRows(3L, 25, 0))
                .thenReturn(List.of());
        when(repository.findTransmissionChildRows(List.of()))
                .thenReturn(List.of());

        PageResponse<AwardSapTransmissionResponse> page =
                service.findSapTransmissions(3L, 0, 25);

        assertThat(page.content()).isEmpty();
    }

    // --- Attachments -----------------------------------------------------

    @Test
    void findAttachmentsDelegatesDirectlyToTheRepository() {
        AwardAttachmentResponse attachment = new AwardAttachmentResponse(
                500L, "100004-00003", 1, "budget.pdf", "application/pdf",
                "Budget justification", "BUD", "COMPLETE", 1024L,
                "UPLOADED", true, LocalDateTime.of(2021, 1, 1, 0, 0)
        );
        when(repository.findAttachments(3L)).thenReturn(List.of(attachment));

        assertThat(service.findAttachments(3L)).containsExactly(attachment);
    }

    @Test
    void downloadAttachmentStreamsTheObjectWhenOwnershipAndStatusMatch() {
        when(repository.findAttachmentAwardId(500L))
                .thenReturn(Optional.of(3L));
        AwardArchivedAttachment archived = new AwardArchivedAttachment(
                500L, 3L, "budget.pdf", "application/pdf",
                "test-bucket", "awards/3/500/budget.pdf", 4L, "UPLOADED"
        );
        when(repository.findArchivedAttachment(3L, 500L))
                .thenReturn(Optional.of(archived));
        when(attachmentStorage.open(archived)).thenReturn(
                new AwardAttachmentStorage.StoredObject(
                        new ByteArrayInputStream(new byte[]{1, 2, 3, 4}), 4L
                )
        );

        AwardAttachmentDownload download =
                service.downloadAttachment(3L, 500L);

        assertThat(download.fileName()).isEqualTo("budget.pdf");
        assertThat(download.mimeType()).isEqualTo("application/pdf");
        assertThat(download.contentLength()).isEqualTo(4L);
    }

    @Test
    void downloadAttachmentRejectsAnAttachmentOwnedByADifferentAward() {
        when(repository.findAttachmentAwardId(500L))
                .thenReturn(Optional.of(999L));

        assertThatThrownBy(() -> service.downloadAttachment(3L, 500L))
                .isInstanceOf(NoSuchElementException.class);
    }

    @Test
    void downloadAttachmentRejectsAnAttachmentThatIsNotYetUploaded() {
        when(repository.findAttachmentAwardId(500L))
                .thenReturn(Optional.of(3L));
        AwardArchivedAttachment pending = new AwardArchivedAttachment(
                500L, 3L, "budget.pdf", "application/pdf",
                null, null, 4L, "PENDING"
        );
        when(repository.findArchivedAttachment(3L, 500L))
                .thenReturn(Optional.of(pending));

        assertThatThrownBy(() -> service.downloadAttachment(3L, 500L))
                .isInstanceOf(NoSuchElementException.class);
    }

    @Test
    void downloadAttachmentRejectsANonPositiveAttachmentId() {
        assertThatThrownBy(() -> service.downloadAttachment(3L, 0L))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
