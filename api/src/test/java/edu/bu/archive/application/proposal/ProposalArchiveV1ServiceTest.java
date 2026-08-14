package edu.bu.archive.application.proposal;

import edu.bu.archive.adapter.in.web.dto.PageResponse;
import edu.bu.archive.adapter.in.web.dto.proposal.ProposalAssociatedUnitResponse;
import edu.bu.archive.adapter.in.web.dto.proposal.ProposalAttachmentResponse;
import edu.bu.archive.adapter.in.web.dto.proposal.ProposalAttachmentsResponse;
import edu.bu.archive.adapter.in.web.dto.proposal.ProposalCommentRow;
import edu.bu.archive.adapter.in.web.dto.proposal.ProposalCommentsResponse;
import edu.bu.archive.adapter.in.web.dto.proposal.ProposalCustomDataResponse;
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
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
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
    void findFundedAwardsResolvesTheWholeFamilyAndCarriesBothAwardIds() {
        when(repository.findProposalNumber(2986L))
                .thenReturn(Optional.of("205"));
        // Real fixture: the link (archive.proposal_award) points at
        // award_id 148155 (award_number 200268-00001's sequence 1 of
        // 5, is_primary_current = FALSE - a long-superseded version),
        // but the repository resolves through award_number to that
        // family's CURRENT version (sequence 5, "Closed") - never the
        // stale linked version's own status. Both IDs are carried:
        // exactLinkedAwardId (148155) for audit, navigableCurrentAwardId
        // (605555) for navigation.
        ProposalFundedAwardResponse fundedAward =
                new ProposalFundedAwardResponse(
                        "200268-00001", "Title", "Closed", 2, 1, 5, true,
                        148155L, 605555L, 148183L
                );
        when(repository.findFundedAwardRows("205"))
                .thenReturn(List.of(fundedAward));

        List<ProposalFundedAwardResponse> result =
                service.findFundedAwards(2986L);

        assertThat(result).containsExactly(fundedAward);
    }

    @Test
    void findFundedAwardsReturnsEveryLinkedAwardWhenAProposalFundsMultipleAwards() {
        // Real fixture: Institutional Proposal 01109910 has three real,
        // distinct AWARD_FUNDING_PROPOSALS rows, all active - every
        // one must be returned, not just the first.
        when(repository.findProposalNumber(1L))
                .thenReturn(Optional.of("01109910"));
        ProposalFundedAwardResponse first = new ProposalFundedAwardResponse(
                "100100-00001", "First", "Active", 1, 1, 1, true, 10L, 10L, 910L
        );
        ProposalFundedAwardResponse second = new ProposalFundedAwardResponse(
                "100200-00001", "Second", "Active", 1, 1, 1, true, 20L, 20L, 920L
        );
        ProposalFundedAwardResponse third = new ProposalFundedAwardResponse(
                "100300-00001", "Third", "Active", 1, 1, 1, true, 30L, 30L, 930L
        );
        when(repository.findFundedAwardRows("01109910"))
                .thenReturn(List.of(first, second, third));

        List<ProposalFundedAwardResponse> result =
                service.findFundedAwards(1L);

        assertThat(result).containsExactly(first, second, third);
    }

    @Test
    void findFundedAwardsPreservesBothSourceRelationshipsForAGenuineNaturalKeyDuplicate() {
        // Live fixture: Proposal family 2975, award_id 462515 - two
        // real, distinct archive.proposal_award rows
        // (award_funding_proposal_id 501508 and 511830) that V075
        // allowed to coexist. The service must never collapse them -
        // grouping visually identical relationships is a presentation
        // concern, not a data-layer one.
        when(repository.findProposalNumber(1L))
                .thenReturn(Optional.of("2975"));
        ProposalFundedAwardResponse older = new ProposalFundedAwardResponse(
                "201498-00001", "Title", "Active", 1, 1, 1, true,
                462515L, 462515L, 501508L
        );
        ProposalFundedAwardResponse newer = new ProposalFundedAwardResponse(
                "201498-00001", "Title", "Active", 1, 1, 1, true,
                462515L, 462515L, 511830L
        );
        when(repository.findFundedAwardRows("2975"))
                .thenReturn(List.of(older, newer));

        List<ProposalFundedAwardResponse> result =
                service.findFundedAwards(1L);

        assertThat(result).hasSize(2);
        assertThat(result)
                .extracting(ProposalFundedAwardResponse::sourceRelationshipId)
                .containsExactly(501508L, 511830L);
    }

    @Test
    void findFundedAwardsPreservesInactiveRelationshipsRatherThanDroppingThem() {
        when(repository.findProposalNumber(2986L))
                .thenReturn(Optional.of("205"));
        ProposalFundedAwardResponse inactive = new ProposalFundedAwardResponse(
                "200268-00001", "Title", "Closed", 2, 1, 5, false,
                148155L, 605555L, 148183L
        );
        when(repository.findFundedAwardRows("205"))
                .thenReturn(List.of(inactive));

        List<ProposalFundedAwardResponse> result =
                service.findFundedAwards(2986L);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).relationshipActive()).isFalse();
    }

    @Test
    void findCustomDataResolvesTheLabelViaTheSharedLookup() {
        // Real fixture: proposal_id 2986 (family 205), custom_attribute_id
        // 480 ("ip_submission_date" / "Submitted Date", data type "Date").
        when(repository.findProposalNumber(2986L))
                .thenReturn(Optional.of("205"));
        ProposalCustomDataResponse row = new ProposalCustomDataResponse(
                477845L, 480L, "Submitted Date", "ip_submission_date",
                "Date", null, "08/09/2011",
                LocalDateTime.of(2017, 5, 3, 11, 31, 39), "dhaywood"
        );
        when(repository.findCustomDataRows(2986L)).thenReturn(List.of(row));

        List<ProposalCustomDataResponse> result = service.findCustomData(2986L);

        assertThat(result).containsExactly(row);
        assertThat(result.get(0).label()).isEqualTo("Submitted Date");
    }

    @Test
    void findCustomDataThrowsNotFoundWhenTheProposalDoesNotExist() {
        when(repository.findProposalNumber(999L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.findCustomData(999L))
                .isInstanceOf(NoSuchElementException.class);
    }

    @Test
    void findCustomDataPreservesARowWithNoMatchingLookupRatherThanDroppingIt() {
        // custom_attribute_id has no foreign key (V064) - Oracle can add
        // an attribute this archive hasn't loaded into
        // archive.custom_attribute yet. The row must still come back,
        // with label/name/dataType null, never silently filtered out.
        when(repository.findProposalNumber(2986L))
                .thenReturn(Optional.of("205"));
        ProposalCustomDataResponse unresolvedRow = new ProposalCustomDataResponse(
                999999L, 424242L, null, null, null, null, "some value",
                LocalDateTime.now(), "dhaywood"
        );
        when(repository.findCustomDataRows(2986L))
                .thenReturn(List.of(unresolvedRow));

        List<ProposalCustomDataResponse> result = service.findCustomData(2986L);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).label()).isNull();
        assertThat(result.get(0).value()).isEqualTo("some value");
    }

    @Test
    void findCustomDataPreservesARealBlankValueDistinctFromNoRowAtAll() {
        // Real fixture: proposal_custom_data_id 1495997 (attribute 1209,
        // "Opportunity Title") has a NULL value - a genuine, persisted
        // blank, not the absence of a row.
        when(repository.findProposalNumber(2986L))
                .thenReturn(Optional.of("205"));
        ProposalCustomDataResponse blankRow = new ProposalCustomDataResponse(
                1495997L, 1209L, "Opportunity Title", "OppTitle",
                "String", null, null,
                LocalDateTime.of(2020, 3, 6, 15, 40, 37), "dhaywood"
        );
        when(repository.findCustomDataRows(2986L))
                .thenReturn(List.of(blankRow));

        List<ProposalCustomDataResponse> result = service.findCustomData(2986L);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).value()).isNull();
        assertThat(result.get(0).label()).isEqualTo("Opportunity Title");
    }

    @Test
    void findCustomDataNeverCombinesRowsAcrossSiblingVersions() {
        // Version scoping proof: the service must query by the exact
        // proposalId only - never resolve to proposalNumber and fan out
        // across the whole family the way findFundedAwards does. Real
        // fixture 01157400 spans 6 different proposal_ids/sequence_numbers
        // - combining them would silently merge unrelated versions' data.
        when(repository.findProposalNumber(1L)).thenReturn(Optional.of("01157400"));
        ProposalCustomDataResponse thisVersionOnly = new ProposalCustomDataResponse(
                1L, 100L, "Label", "name", "String", null, "v1 value",
                LocalDateTime.now(), "dhaywood"
        );
        when(repository.findCustomDataRows(1L)).thenReturn(List.of(thisVersionOnly));

        List<ProposalCustomDataResponse> result = service.findCustomData(1L);

        assertThat(result).containsExactly(thisVersionOnly);
        verify(repository).findCustomDataRows(1L);
        verify(repository, never()).findCustomDataRows(2L);
    }

    @Test
    void findCustomDataReturnsALargeSetWithoutTruncation() {
        // Real fixture 01157400 has 161 rows for a single proposal_id -
        // the service must not cap or drop any of them.
        when(repository.findProposalNumber(1238613L))
                .thenReturn(Optional.of("01157400"));
        List<ProposalCustomDataResponse> rows = new java.util.ArrayList<>();
        for (long i = 0; i < 161; i++) {
            rows.add(new ProposalCustomDataResponse(
                    i, i, "Label " + i, "name" + i, "String", null,
                    "value " + i, LocalDateTime.now(), "dhaywood"
            ));
        }
        when(repository.findCustomDataRows(1238613L)).thenReturn(rows);

        List<ProposalCustomDataResponse> result =
                service.findCustomData(1238613L);

        assertThat(result).hasSize(161);
    }
}
