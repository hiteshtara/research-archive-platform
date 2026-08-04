package edu.bu.archive.application.proposal;

import edu.bu.archive.adapter.in.web.dto.PageResponse;
import edu.bu.archive.adapter.in.web.dto.proposal.ProposalAssociatedUnitResponse;
import edu.bu.archive.adapter.in.web.dto.proposal.ProposalAttachmentResponse;
import edu.bu.archive.adapter.in.web.dto.proposal.ProposalAttachmentsResponse;
import edu.bu.archive.adapter.in.web.dto.proposal.ProposalCommentRow;
import edu.bu.archive.adapter.in.web.dto.proposal.ProposalCommentsResponse;
import edu.bu.archive.adapter.in.web.dto.proposal.ProposalFundedAwardResponse;
import edu.bu.archive.adapter.in.web.dto.proposal.ProposalPersonResponse;
import edu.bu.archive.adapter.in.web.dto.proposal.ProposalSummaryResponse;
import edu.bu.archive.adapter.in.web.dto.proposal.ProposalUnitContactResponse;
import edu.bu.archive.adapter.in.web.dto.proposal.ProposalUnitsResponse;
import edu.bu.archive.adapter.in.web.dto.proposal.ProposalVersionSummaryResponse;
import edu.bu.archive.adapter.out.persistence.ProposalArchivedAttachment;
import edu.bu.archive.adapter.out.persistence.ProposalAttachmentStorage;
import edu.bu.archive.adapter.out.persistence.ProposalV1Repository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.time.LocalDateTime;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ProposalArchiveV1ServiceTest {

    private ProposalV1Repository repository;
    private ProposalAttachmentStorage attachmentStorage;
    private ProposalArchiveV1Service service;

    @BeforeEach
    void setUp() {
        repository = mock(ProposalV1Repository.class);
        attachmentStorage = mock(ProposalAttachmentStorage.class);
        service = new ProposalArchiveV1Service(repository, attachmentStorage);
    }

    @Test
    void findSummaryReturnsTheResolvedSummary() {
        ProposalSummaryResponse summary = new ProposalSummaryResponse(
                1238613L, "01157400", 7, "125761", "Title", "Funded",
                "ACTIVE", "Type", "Activity", "1262160000", "Lead Unit",
                "S1", "Sponsor", "U1", "PI", null, null, null, null, null,
                null, null, null, null, null
        );
        when(repository.findSummary(1238613L))
                .thenReturn(Optional.of(summary));

        assertThat(service.findSummary(1238613L)).isEqualTo(summary);
    }

    @Test
    void findSummaryThrowsNotFoundWhenTheProposalDoesNotExist() {
        when(repository.findSummary(999L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.findSummary(999L))
                .isInstanceOf(NoSuchElementException.class);
    }

    @Test
    void findVersionsResolvesTheFamilyAndBuildsAPageResponse() {
        when(repository.findProposalNumber(2986L))
                .thenReturn(Optional.of("205"));
        when(repository.countVersions("205")).thenReturn(2L);
        ProposalVersionSummaryResponse version = new ProposalVersionSummaryResponse(
                2986L, "205", 2, "125761", "ACTIVE", "Funded", "Title",
                LocalDateTime.now()
        );
        when(repository.findVersionRows("205", 50, 0))
                .thenReturn(List.of(version));

        PageResponse<ProposalVersionSummaryResponse> page =
                service.findVersions(2986L, 0, 50);

        assertThat(page.content()).containsExactly(version);
        assertThat(page.totalElements()).isEqualTo(2L);
    }

    @Test
    void findVersionsThrowsNotFoundWhenTheProposalDoesNotExist() {
        when(repository.findProposalNumber(999L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.findVersions(999L, 0, 50))
                .isInstanceOf(NoSuchElementException.class);
    }

    @Test
    void findUnitsKeepsAssociatedUnitsAndUnitContactsAsDistinctLists() {
        when(repository.findProposalNumber(212L))
                .thenReturn(Optional.of("205"));
        ProposalAssociatedUnitResponse associatedUnit =
                new ProposalAssociatedUnitResponse(
                        126592L, 126591L, "LOIS K HORWITZ", "1262160000",
                        "MET ACTUARIAL SCIENCE", true
                );
        ProposalUnitContactResponse unitContact =
                new ProposalUnitContactResponse(
                        204L, "U19663726", "ANDREA COZZI", "1",
                        "Pre-Award - Department Administrator", "CONTACT"
                );
        when(repository.findAssociatedUnitRows(212L))
                .thenReturn(List.of(associatedUnit));
        when(repository.findUnitContactRows(212L))
                .thenReturn(List.of(unitContact));

        ProposalUnitsResponse units = service.findUnits(212L);

        assertThat(units.associatedUnits()).containsExactly(associatedUnit);
        assertThat(units.unitContacts()).containsExactly(unitContact);
        // A different real person than the PI - never merged.
        assertThat(unitContact.personId())
                .isNotEqualTo(associatedUnit.personName());
    }

    @Test
    void findAttachmentsGroupsByTheRealOracleTypeNotByTitle() {
        when(repository.findProposalNumber(1238613L))
                .thenReturn(Optional.of("01157400"));
        ProposalAttachmentResponse guidelines = new ProposalAttachmentResponse(
                86484L, 7, 4, "Ryan_NSF_1.11.17_Guidelines", 7, "Other",
                "Ryan_NSF_1.11.17_Guidelines.pdf", "application/pdf", null,
                161165L, "UPLOADED", true, LocalDateTime.now()
        );
        ProposalAttachmentResponse transferPackage = new ProposalAttachmentResponse(
                86488L, 7, 8, "Cornell Transfer Package", 4,
                "Proposal Package", "Ryan_NSF_Transfer_Submission_12.21.18.pdf",
                "application/pdf", null, 726324L, "UPLOADED", true,
                LocalDateTime.now()
        );
        when(repository.findAttachmentRows(1238613L))
                .thenReturn(List.of(guidelines, transferPackage));

        ProposalAttachmentsResponse response =
                service.findAttachments(1238613L);

        assertThat(response.groups()).hasSize(2);
        assertThat(response.groups())
                .anySatisfy(group -> {
                    assertThat(group.attachmentTypeCode()).isEqualTo(7);
                    assertThat(group.attachmentTypeDescription())
                            .isEqualTo("Other");
                    assertThat(group.attachments())
                            .containsExactly(guidelines);
                })
                .anySatisfy(group -> {
                    assertThat(group.attachmentTypeCode()).isEqualTo(4);
                    assertThat(group.attachmentTypeDescription())
                            .isEqualTo("Proposal Package");
                    assertThat(group.attachments())
                            .containsExactly(transferPackage);
                });
    }

    @Test
    void downloadAttachmentStreamsTheArchivedObject() {
        when(repository.findProposalNumber(1238613L))
                .thenReturn(Optional.of("01157400"));
        when(repository.findAttachmentProposalId(86484L))
                .thenReturn(Optional.of(1238613L));
        ProposalArchivedAttachment archived = new ProposalArchivedAttachment(
                86484L, 1238613L, "Ryan_NSF_1.11.17_Guidelines.pdf",
                "application/pdf", "documents", "proposal/01157400/7/86484/x.pdf",
                161165L, "UPLOADED"
        );
        when(repository.findArchivedAttachment(1238613L, 86484L))
                .thenReturn(Optional.of(archived));
        when(attachmentStorage.open(archived)).thenReturn(
                new ProposalAttachmentStorage.StoredObject(
                        new ByteArrayInputStream(new byte[]{1, 2, 3}), 3
                )
        );

        ProposalAttachmentDownload download =
                service.downloadAttachment(1238613L, 86484L);

        assertThat(download.fileName())
                .isEqualTo("Ryan_NSF_1.11.17_Guidelines.pdf");
        assertThat(download.mimeType()).isEqualTo("application/pdf");
        assertThat(download.contentLength()).isEqualTo(3);
    }

    @Test
    void downloadAttachmentThrowsNotFoundWhenTheAttachmentBelongsToADifferentProposal() {
        when(repository.findProposalNumber(1238613L))
                .thenReturn(Optional.of("01157400"));
        when(repository.findAttachmentProposalId(86484L))
                .thenReturn(Optional.of(1179677L));

        assertThatThrownBy(() ->
                service.downloadAttachment(1238613L, 86484L)
        ).isInstanceOf(NoSuchElementException.class);
    }

    @Test
    void findCommentsCollapsesConsecutiveIdenticalTextToItsEarliestOccurrence() {
        when(repository.findProposalNumber(2986L))
                .thenReturn(Optional.of("205"));
        LocalDateTime older = LocalDateTime.of(2011, 8, 10, 12, 46);
        LocalDateTime newer = LocalDateTime.of(2011, 9, 7, 16, 32);
        ProposalCommentRow newRow = new ProposalCommentRow(
                434L, 2986L, 2, "12", "Proposal Comments",
                "Continuation of BU source #5039-5.", newer, "dmarkey"
        );
        ProposalCommentRow oldRow = new ProposalCommentRow(
                433L, 212L, 1, "12", "Proposal Comments",
                "Continuation of BU source #5039-5.", older, "baccari"
        );
        ProposalCommentRow noCommentType13 = new ProposalCommentRow(
                null, null, null, "13", "Proposal IP Review Comments",
                null, null, null
        );
        when(repository.findCommentRows("205"))
                .thenReturn(List.of(newRow, oldRow, noCommentType13));

        ProposalCommentsResponse response = service.findComments(2986L);

        assertThat(response.commentCategories()).hasSize(2);
        assertThat(response.commentCategories())
                .anySatisfy(category -> {
                    assertThat(category.commentTypeCode()).isEqualTo("12");
                    assertThat(category.history()).hasSize(1);
                    assertThat(category.current().proposalCommentId())
                            .isEqualTo(433L);
                })
                .anySatisfy(category -> {
                    assertThat(category.commentTypeCode()).isEqualTo("13");
                    assertThat(category.current()).isNull();
                    assertThat(category.history()).isEmpty();
                });
    }

    @Test
    void findFundedAwardsResolvesTheWholeFamilyAndNeverExposesAnAwardId() {
        when(repository.findProposalNumber(2986L))
                .thenReturn(Optional.of("205"));
        ProposalFundedAwardResponse fundedAward =
                new ProposalFundedAwardResponse("200268-00001", 1, "Active");
        when(repository.findFundedAwardRows("205"))
                .thenReturn(List.of(fundedAward));

        List<ProposalFundedAwardResponse> result =
                service.findFundedAwards(2986L);

        assertThat(result).containsExactly(fundedAward);
    }
}
