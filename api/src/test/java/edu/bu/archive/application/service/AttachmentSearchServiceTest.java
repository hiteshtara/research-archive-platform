package edu.bu.archive.application.service;

import edu.bu.archive.adapter.in.web.dto.PageResponse;
import edu.bu.archive.adapter.in.web.dto.attachment.AttachmentSearchResultResponse;
import edu.bu.archive.adapter.in.web.dto.attachment.AttachmentSearchRow;
import edu.bu.archive.adapter.in.web.dto.attachment.MixedAttachmentSearchRow;
import edu.bu.archive.adapter.out.persistence.AttachmentSearchRepository;
import edu.bu.archive.application.award.AwardArchiveService;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class AttachmentSearchServiceTest {

    private AwardArchiveService awardArchiveService;
    private AttachmentSearchRepository repository;
    private AttachmentSearchService service;

    // Real fixture: Proposal family 2975, proposal_id 7125, version 4,
    // attachment_id 501508 - the same duplicate-relationship fixture
    // proven live this session (V075).
    private static final AttachmentSearchRow PROPOSAL_ROW = new AttachmentSearchRow(
            7125L, "2975", "Coulter Translational Partnership", "PI NAME", 4,
            "879423", 501508L, null, "Notice.pdf",
            "Notice of Award", null, 1_800_000L, "application/pdf",
            "UPLOADED", true, true
    );

    @BeforeEach
    void setUp() {
        awardArchiveService = mock(AwardArchiveService.class);
        repository = mock(AttachmentSearchRepository.class);
        service = new AttachmentSearchService(awardArchiveService, repository);
    }

    // --- Backward compatibility: recordType omitted or AWARD delegates verbatim ---

    @Test
    void omittedRecordTypeDelegatesToAwardArchiveServiceUnchanged() {
        PageResponse<AttachmentSearchResultResponse> page =
                new PageResponse<>(List.of(), 0, 25, 0L, 0, true, true);
        when(awardArchiveService.searchAttachments(
                "200086-00001", "", "", null, "", "all", 0, 25
        )).thenReturn(page);

        PageResponse<AttachmentSearchResultResponse> result = service.searchAttachments(
                null, null, "200086-00001", null, null, null, null, null, "all", 0, 25
        );

        assertThat(result).isSameAs(page);
        verify(awardArchiveService).searchAttachments(
                "200086-00001", "", "", null, "", "all", 0, 25
        );
        verifyNoInteractions(repository);
    }

    @Test
    void explicitRecordTypeAwardDelegatesToAwardArchiveService() {
        PageResponse<AttachmentSearchResultResponse> page =
                new PageResponse<>(List.of(), 0, 25, 0L, 0, true, true);
        when(awardArchiveService.searchAttachments(
                "200086-00001", "", "", null, "", "all", 0, 25
        )).thenReturn(page);

        service.searchAttachments(
                "AWARD", "200086-00001", null, null, null, null, null, null, "all", 0, 25
        );

        verify(awardArchiveService).searchAttachments(
                "200086-00001", "", "", null, "", "all", 0, 25
        );
    }

    @Test
    void recordTypeIsCaseInsensitive() {
        PageResponse<AttachmentSearchResultResponse> page =
                new PageResponse<>(List.of(), 0, 25, 0L, 0, true, true);
        when(awardArchiveService.searchAttachments(
                "200086-00001", "", "", null, "", "all", 0, 25
        )).thenReturn(page);

        service.searchAttachments(
                "award", "200086-00001", null, null, null, null, null, null, "all", 0, 25
        );

        verify(awardArchiveService).searchAttachments(
                "200086-00001", "", "", null, "", "all", 0, 25
        );
    }

    @Test
    void awardNumberAliasStillWorksWhenRecordNumberIsBlank() {
        PageResponse<AttachmentSearchResultResponse> page =
                new PageResponse<>(List.of(), 0, 25, 0L, 0, true, true);
        when(awardArchiveService.searchAttachments(
                "200086-00001", "", "", null, "", "all", 0, 25
        )).thenReturn(page);

        service.searchAttachments(
                null, null, "200086-00001", null, null, null, null, null, "all", 0, 25
        );

        verify(awardArchiveService).searchAttachments(
                "200086-00001", "", "", null, "", "all", 0, 25
        );
    }

    @Test
    void recordNumberAndAwardNumberAliasWithTheSameValueAreAccepted() {
        PageResponse<AttachmentSearchResultResponse> page =
                new PageResponse<>(List.of(), 0, 25, 0L, 0, true, true);
        when(awardArchiveService.searchAttachments(
                "200086-00001", "", "", null, "", "all", 0, 25
        )).thenReturn(page);

        service.searchAttachments(
                "AWARD", "200086-00001", "200086-00001", null, null, null, null, null, "all", 0, 25
        );

        verify(awardArchiveService).searchAttachments(
                "200086-00001", "", "", null, "", "all", 0, 25
        );
    }

    @Test
    void conflictingRecordNumberAndAwardNumberAliasValuesAreRejected() {
        assertThatThrownBy(() ->
                service.searchAttachments(
                        "AWARD", "canonical-value", "legacy-value", null, null, null, null, null, "all", 0, 25
                )
        )
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("recordNumber/awardNumber")
                .hasMessageContaining("canonical-value")
                .hasMessageContaining("legacy-value");

        verifyNoInteractions(awardArchiveService, repository);
    }

    @Test
    void recordIdAndAwardIdAliasWithTheSameValueAreAccepted() {
        when(repository.countSearchProposalAttachments(
                anyString(), anyString(), eq(7125L), isNull(), eq("all")
        )).thenReturn(0L);
        when(repository.searchProposalAttachments(
                anyString(), anyString(), eq(7125L), isNull(), eq("all"), anyString(), anyInt(), anyInt()
        )).thenReturn(List.of());

        service.searchAttachments(
                "PROPOSAL", null, null, null, "7125", "7125", null, null, "all", 0, 25
        );

        verify(repository).countSearchProposalAttachments(
                anyString(), anyString(), eq(7125L), isNull(), eq("all")
        );
    }

    @Test
    void conflictingRecordIdAndAwardIdAliasValuesAreRejected() {
        assertThatThrownBy(() ->
                service.searchAttachments(
                        "PROPOSAL", "2975", null, null, "7125", "9999", null, null, "all", 0, 25
                )
        )
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("recordId/awardId")
                .hasMessageContaining("7125")
                .hasMessageContaining("9999");

        verifyNoInteractions(awardArchiveService, repository);
    }

    @Test
    void invalidRecordTypeIsRejected() {
        assertThatThrownBy(() ->
                service.searchAttachments(
                        "SUBAWARD", "200086-00001", null, null, null, null, null, null, "all", 0, 25
                )
        )
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("recordType")
                .hasMessageContaining("SUBAWARD");

        verifyNoInteractions(awardArchiveService, repository);
    }

    // --- Proposal search ---

    @Test
    void proposalNumberExactSearch() {
        when(repository.countSearchProposalAttachments(
                eq("2975"), anyString(), isNull(), isNull(), eq("all")
        )).thenReturn(1L);
        when(repository.searchProposalAttachments(
                eq("2975"), anyString(), isNull(), isNull(), eq("all"), anyString(), eq(25), eq(0)
        )).thenReturn(List.of(PROPOSAL_ROW));

        PageResponse<AttachmentSearchResultResponse> result = service.searchAttachments(
                "PROPOSAL", "2975", null, null, null, null, null, null, "all", 0, 25
        );

        assertThat(result.content()).hasSize(1);
        AttachmentSearchResultResponse row = result.content().get(0);
        assertThat(row.recordType()).isEqualTo("PROPOSAL");
        assertThat(row.parentNumber()).isEqualTo("2975");
        assertThat(row.fileId()).isNull();
        verifyNoInteractions(awardArchiveService);
    }

    @Test
    void proposalWorkflowDocumentNumberExactSearch() {
        when(repository.countSearchProposalAttachments(
                anyString(), eq("879423"), isNull(), isNull(), eq("all")
        )).thenReturn(1L);
        when(repository.searchProposalAttachments(
                anyString(), eq("879423"), isNull(), isNull(), eq("all"), anyString(), eq(25), eq(0)
        )).thenReturn(List.of(PROPOSAL_ROW));

        PageResponse<AttachmentSearchResultResponse> result = service.searchAttachments(
                "PROPOSAL", null, null, "879423", null, null, null, null, "all", 0, 25
        );

        assertThat(result.content()).hasSize(1);
        assertThat(result.content().get(0).workflowDocumentNumber()).isEqualTo("879423");
    }

    @Test
    void proposalIdExactSearch() {
        when(repository.countSearchProposalAttachments(
                anyString(), anyString(), eq(7125L), isNull(), eq("all")
        )).thenReturn(1L);
        when(repository.searchProposalAttachments(
                anyString(), anyString(), eq(7125L), isNull(), eq("all"), anyString(), eq(25), eq(0)
        )).thenReturn(List.of(PROPOSAL_ROW));

        PageResponse<AttachmentSearchResultResponse> result = service.searchAttachments(
                "PROPOSAL", null, null, null, "7125", null, null, null, "all", 0, 25
        );

        assertThat(result.content()).hasSize(1);
        assertThat(result.content().get(0).parentId()).isEqualTo(7125L);
    }

    @Test
    void proposalAttachmentIdExactSearchWithRecordTypeProposal() {
        when(repository.countSearchProposalAttachments(
                anyString(), anyString(), isNull(), eq(501508L), eq("all")
        )).thenReturn(1L);
        when(repository.searchProposalAttachments(
                anyString(), anyString(), isNull(), eq(501508L), eq("all"), anyString(), eq(25), eq(0)
        )).thenReturn(List.of(PROPOSAL_ROW));

        PageResponse<AttachmentSearchResultResponse> result = service.searchAttachments(
                "PROPOSAL", null, null, null, null, null, "501508", null, "all", 0, 25
        );

        assertThat(result.content()).hasSize(1);
        assertThat(result.content().get(0).attachmentId()).isEqualTo(501508L);
    }

    @Test
    void proposalIdAliasAwardIdWorksWhenRecordIdIsBlank() {
        when(repository.countSearchProposalAttachments(
                anyString(), anyString(), eq(7125L), isNull(), eq("all")
        )).thenReturn(0L);
        when(repository.searchProposalAttachments(
                anyString(), anyString(), eq(7125L), isNull(), eq("all"), anyString(), anyInt(), anyInt()
        )).thenReturn(List.of());

        service.searchAttachments(
                "PROPOSAL", null, null, null, null, "7125", null, null, "all", 0, 25
        );

        verify(repository).countSearchProposalAttachments(
                anyString(), anyString(), eq(7125L), isNull(), eq("all")
        );
    }

    @Test
    void proposalRejectsAnInvalidNumericProposalId() {
        assertThatThrownBy(() ->
                service.searchAttachments(
                        "PROPOSAL", null, null, null, "not-a-number", null, null, null, "all", 0, 25
                )
        )
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Proposal ID")
                .hasMessageContaining("not-a-number");

        verifyNoInteractions(repository);
    }

    @Test
    void proposalRejectsAllBlankIdentifiers() {
        assertThatThrownBy(() ->
                service.searchAttachments(
                        "PROPOSAL", "", null, "  ", null, null, null, null, "all", 0, 25
                )
        )
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("At least one identifier");

        verifyNoInteractions(repository);
    }

    // --- Rule: fileId is Award-specific ---

    @Test
    void fileIdWithRecordTypeProposalIsRejected() {
        assertThatThrownBy(() ->
                service.searchAttachments(
                        "PROPOSAL", "2975", null, null, null, null, null, "5001", "all", 0, 25
                )
        )
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("fileId")
                .hasMessageContaining("PROPOSAL");

        verifyNoInteractions(repository, awardArchiveService);
    }

    @Test
    void fileIdWithRecordTypeAllIsRejected() {
        assertThatThrownBy(() ->
                service.searchAttachments(
                        "ALL", "200086-00001", null, null, null, null, null, "5001", "all", 0, 25
                )
        )
                .isInstanceOf(IllegalArgumentException.class);

        verifyNoInteractions(repository, awardArchiveService);
    }

    // --- Rule: attachmentId/recordId/fileId ambiguous for ALL ---

    @Test
    void attachmentIdWithoutASpecificRecordTypeIsRejected() {
        assertThatThrownBy(() ->
                service.searchAttachments(
                        "ALL", "200086-00001", null, null, null, null, "9001", null, "all", 0, 25
                )
        )
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ambiguous");

        verifyNoInteractions(repository, awardArchiveService);
    }

    @Test
    void recordIdWithRecordTypeAllIsRejected() {
        assertThatThrownBy(() ->
                service.searchAttachments(
                        "ALL", "200086-00001", null, null, "123", null, null, null, "all", 0, 25
                )
        )
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ambiguous");

        verifyNoInteractions(repository, awardArchiveService);
    }

    // --- Mixed ALL search ---

    @Test
    void mixedAllSearchReturnsAwardAndProposalResults() {
        MixedAttachmentSearchRow awardRow = new MixedAttachmentSearchRow(
                "AWARD", 3047454L, "200086-00001", "Award Title", "PI", 165,
                "879423", 9001L, 5001L, "Notice of Award.pdf", "Notice of Award",
                null, 1_800_000L, "application/pdf", "UPLOADED", true, true
        );
        MixedAttachmentSearchRow proposalRow = new MixedAttachmentSearchRow(
                "PROPOSAL", 7125L, "2975", "Proposal Title", "PI", 4,
                "879423", 501508L, null, "Notice.pdf", "Notice",
                null, 1_800_000L, "application/pdf", "UPLOADED", true, true
        );
        when(repository.countSearchAllAttachments(anyString(), anyString(), eq("all")))
                .thenReturn(2L);
        when(repository.searchAllAttachments(
                anyString(), anyString(), eq("all"), anyString(), eq(25), eq(0)
        )).thenReturn(List.of(awardRow, proposalRow));

        PageResponse<AttachmentSearchResultResponse> result = service.searchAttachments(
                "ALL", "879423", null, "879423", null, null, null, null, "all", 0, 25
        );

        assertThat(result.content()).hasSize(2);
        assertThat(result.content())
                .extracting(AttachmentSearchResultResponse::recordType)
                .containsExactly("AWARD", "PROPOSAL");
        verifyNoInteractions(awardArchiveService);
    }

    @Test
    void mixedAllSearchUsesOneDatabaseLevelUnionOneCountQueryAndDeterministicOrdering() {
        when(repository.countSearchAllAttachments(eq("879423"), eq(""), eq("all")))
                .thenReturn(0L);
        when(repository.searchAllAttachments(
                eq("879423"), eq(""), eq("all"), anyString(), eq(25), eq(0)
        )).thenReturn(List.of());

        service.searchAttachments(
                "ALL", "879423", null, null, null, null, null, null, "all", 0, 25
        );

        // Exactly one count call and one search call - never two
        // independently paginated per-domain queries merged client-side.
        verify(repository, org.mockito.Mockito.times(1))
                .countSearchAllAttachments(anyString(), anyString(), anyString());
        verify(repository, org.mockito.Mockito.times(1))
                .searchAllAttachments(anyString(), anyString(), anyString(), anyString(), anyInt(), anyInt());

        org.mockito.ArgumentCaptor<String> sortCaptor =
                org.mockito.ArgumentCaptor.forClass(String.class);
        verify(repository).searchAllAttachments(
                anyString(), anyString(), anyString(), sortCaptor.capture(), anyInt(), anyInt()
        );
        assertThat(sortCaptor.getValue())
                .contains("parent_number")
                .contains("sequence_number")
                .contains("record_type")
                .contains("attachment_id");
    }

    @Test
    void mixedAllSearchPaginationIsStable() {
        when(repository.countSearchAllAttachments(anyString(), anyString(), eq("all")))
                .thenReturn(150L);
        when(repository.searchAllAttachments(
                anyString(), anyString(), eq("all"), anyString(), eq(25), eq(50)
        )).thenReturn(List.of());

        PageResponse<AttachmentSearchResultResponse> result = service.searchAttachments(
                "ALL", "879423", null, null, null, null, null, null, "all", 2, 25
        );

        assertThat(result.page()).isEqualTo(2);
        assertThat(result.totalElements()).isEqualTo(150L);
        assertThat(result.totalPages()).isEqualTo(6);
        verify(repository).searchAllAttachments(
                anyString(), anyString(), eq("all"), anyString(), eq(25), eq(50)
        );
    }

    @Test
    void mixedAllSearchRejectsAllBlankIdentifiers() {
        assertThatThrownBy(() ->
                service.searchAttachments(
                        "ALL", "", null, "  ", null, null, null, null, "all", 0, 25
                )
        )
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("At least one identifier");

        verifyNoInteractions(repository, awardArchiveService);
    }

    // --- Version filtering ---

    @Test
    void proposalCurrentVersionFilterIsPassedThroughAsGiven() {
        when(repository.countSearchProposalAttachments(
                anyString(), anyString(), eq(7125L), isNull(), eq("current")
        )).thenReturn(0L);
        when(repository.searchProposalAttachments(
                anyString(), anyString(), eq(7125L), isNull(), eq("current"), anyString(), anyInt(), anyInt()
        )).thenReturn(List.of());

        service.searchAttachments(
                "PROPOSAL", null, null, null, "7125", null, null, null, "current", 0, 25
        );

        verify(repository).countSearchProposalAttachments(
                anyString(), anyString(), eq(7125L), isNull(), eq("current")
        );
    }

    @Test
    void proposalHistoricalVersionFilterIsPassedThroughAsGiven() {
        when(repository.countSearchProposalAttachments(
                anyString(), anyString(), eq(7125L), isNull(), eq("historical")
        )).thenReturn(0L);
        when(repository.searchProposalAttachments(
                anyString(), anyString(), eq(7125L), isNull(), eq("historical"), anyString(), anyInt(), anyInt()
        )).thenReturn(List.of());

        service.searchAttachments(
                "PROPOSAL", null, null, null, "7125", null, null, null, "historical", 0, 25
        );

        verify(repository).countSearchProposalAttachments(
                anyString(), anyString(), eq(7125L), isNull(), eq("historical")
        );
    }

    @Test
    void proposalUnrecognizedVersionFilterNormalizesToAll() {
        when(repository.countSearchProposalAttachments(
                anyString(), anyString(), eq(7125L), isNull(), eq("all")
        )).thenReturn(0L);
        when(repository.searchProposalAttachments(
                anyString(), anyString(), eq(7125L), isNull(), eq("all"), anyString(), anyInt(), anyInt()
        )).thenReturn(List.of());

        service.searchAttachments(
                "PROPOSAL", null, null, null, "7125", null, null, null, "not-a-real-filter", 0, 25
        );

        verify(repository).countSearchProposalAttachments(
                anyString(), anyString(), eq(7125L), isNull(), eq("all")
        );
    }

    @Test
    void versionFilterIsCaseInsensitive() {
        when(repository.countSearchProposalAttachments(
                anyString(), anyString(), eq(7125L), isNull(), eq("current")
        )).thenReturn(0L);
        when(repository.searchProposalAttachments(
                anyString(), anyString(), eq(7125L), isNull(), eq("current"), anyString(), anyInt(), anyInt()
        )).thenReturn(List.of());

        service.searchAttachments(
                "PROPOSAL", null, null, null, "7125", null, null, null, "CURRENT", 0, 25
        );

        verify(repository).countSearchProposalAttachments(
                anyString(), anyString(), eq(7125L), isNull(), eq("current")
        );
    }

    // --- Availability mapping (Proposal's own vocabulary differs from Award's) ---

    @Test
    void proposalNotRequestedMapsToPendingUpload() {
        AttachmentSearchRow notRequested = new AttachmentSearchRow(
                7125L, "2975", "Title", "PI", 4, "879423",
                501509L, null, "Budget.xlsx", "Budget", null, 42_000L,
                "application/vnd.ms-excel", "NOT_REQUESTED", false, true
        );
        when(repository.countSearchProposalAttachments(
                anyString(), anyString(), eq(7125L), isNull(), eq("all")
        )).thenReturn(1L);
        when(repository.searchProposalAttachments(
                anyString(), anyString(), eq(7125L), isNull(), eq("all"), anyString(), anyInt(), anyInt()
        )).thenReturn(List.of(notRequested));

        PageResponse<AttachmentSearchResultResponse> result = service.searchAttachments(
                "PROPOSAL", null, null, null, "7125", null, null, null, "all", 0, 25
        );

        assertThat(result.content().get(0).availabilityStatus()).isEqualTo("Pending upload");
        assertThat(result.content().get(0).downloadable()).isFalse();
    }

    @Test
    void proposalInProgressMapsToSourceFileUnavailableNotPendingUpload() {
        // IN_PROGRESS is a real, distinct Proposal status from
        // NOT_REQUESTED - it must not be conflated with Award's own
        // "pending" literal (PENDING), matching Award's own equally
        // narrow single-value check (Award's UPLOADING isn't treated as
        // "Pending upload" either).
        AttachmentSearchRow inProgress = new AttachmentSearchRow(
                7125L, "2975", "Title", "PI", 4, "879423",
                501510L, null, "File.pdf", "Doc", null, 1000L,
                "application/pdf", "IN_PROGRESS", false, true
        );
        when(repository.countSearchProposalAttachments(
                anyString(), anyString(), eq(7125L), isNull(), eq("all")
        )).thenReturn(1L);
        when(repository.searchProposalAttachments(
                anyString(), anyString(), eq(7125L), isNull(), eq("all"), anyString(), anyInt(), anyInt()
        )).thenReturn(List.of(inProgress));

        PageResponse<AttachmentSearchResultResponse> result = service.searchAttachments(
                "PROPOSAL", null, null, null, "7125", null, null, null, "all", 0, 25
        );

        assertThat(result.content().get(0).availabilityStatus())
                .isEqualTo("Source file unavailable");
    }

    @Test
    void proposalFailedMapsToFailed() {
        AttachmentSearchRow failed = new AttachmentSearchRow(
                7125L, "2975", "Title", "PI", 4, "879423",
                501511L, null, "File.pdf", "Doc", null, 1000L,
                "application/pdf", "FAILED", false, true
        );
        when(repository.countSearchProposalAttachments(
                anyString(), anyString(), eq(7125L), isNull(), eq("all")
        )).thenReturn(1L);
        when(repository.searchProposalAttachments(
                anyString(), anyString(), eq(7125L), isNull(), eq("all"), anyString(), anyInt(), anyInt()
        )).thenReturn(List.of(failed));

        PageResponse<AttachmentSearchResultResponse> result = service.searchAttachments(
                "PROPOSAL", null, null, null, "7125", null, null, null, "all", 0, 25
        );

        assertThat(result.content().get(0).availabilityStatus()).isEqualTo("Failed");
    }

    @Test
    void proposalMissingSourceMapsToSourceFileUnavailable() {
        AttachmentSearchRow missingSource = new AttachmentSearchRow(
                7125L, "2975", "Title", "PI", 4, "879423",
                501512L, null, "File.pdf", "Doc", null, 1000L,
                "application/pdf", "MISSING_SOURCE", false, true
        );
        when(repository.countSearchProposalAttachments(
                anyString(), anyString(), eq(7125L), isNull(), eq("all")
        )).thenReturn(1L);
        when(repository.searchProposalAttachments(
                anyString(), anyString(), eq(7125L), isNull(), eq("all"), anyString(), anyInt(), anyInt()
        )).thenReturn(List.of(missingSource));

        PageResponse<AttachmentSearchResultResponse> result = service.searchAttachments(
                "PROPOSAL", null, null, null, "7125", null, null, null, "all", 0, 25
        );

        assertThat(result.content().get(0).availabilityStatus())
                .isEqualTo("Source file unavailable");
    }

    // --- PI resolution ---

    @Test
    void missingProposalPiRemainsNullNeverFabricated() {
        AttachmentSearchRow noPi = new AttachmentSearchRow(
                7125L, "2975", "Title", null, 4, "879423",
                501513L, null, "File.pdf", "Doc", null, 1000L,
                "application/pdf", "UPLOADED", true, true
        );
        when(repository.countSearchProposalAttachments(
                anyString(), anyString(), eq(7125L), isNull(), eq("all")
        )).thenReturn(1L);
        when(repository.searchProposalAttachments(
                anyString(), anyString(), eq(7125L), isNull(), eq("all"), anyString(), anyInt(), anyInt()
        )).thenReturn(List.of(noPi));

        PageResponse<AttachmentSearchResultResponse> result = service.searchAttachments(
                "PROPOSAL", null, null, null, "7125", null, null, null, "all", 0, 25
        );

        assertThat(result.content().get(0).principalInvestigator()).isNull();
    }

    @Test
    void proposalPiResolutionNeverMultipliesAttachmentRows() {
        // Two distinct attachments on the same proposal_id - each must
        // produce exactly one result row, regardless of how many
        // proposal_person/proposal_person_unit rows exist for the
        // family (the repository query joins proposal_version 1:1 by
        // proposal_id, never proposal_person, so this is structurally
        // guaranteed - this test proves the service layer preserves
        // that guarantee rather than introducing its own fan-out).
        AttachmentSearchRow first = PROPOSAL_ROW;
        AttachmentSearchRow second = new AttachmentSearchRow(
                7125L, "2975", "Title", "PI NAME", 4, "879423",
                501514L, null, "Second.pdf", "Doc", null, 2000L,
                "application/pdf", "UPLOADED", true, true
        );
        when(repository.countSearchProposalAttachments(
                anyString(), anyString(), eq(7125L), isNull(), eq("all")
        )).thenReturn(2L);
        when(repository.searchProposalAttachments(
                anyString(), anyString(), eq(7125L), isNull(), eq("all"), anyString(), anyInt(), anyInt()
        )).thenReturn(List.of(first, second));

        PageResponse<AttachmentSearchResultResponse> result = service.searchAttachments(
                "PROPOSAL", null, null, null, "7125", null, null, null, "all", 0, 25
        );

        assertThat(result.content()).hasSize(2);
        assertThat(result.content())
                .extracting(AttachmentSearchResultResponse::attachmentId)
                .containsExactly(501508L, 501514L);
        assertThat(result.content())
                .allSatisfy(row -> assertThat(row.principalInvestigator()).isEqualTo("PI NAME"));
    }

    // --- Structural: Proposal storage fields never exposed ---

    @Test
    void responseNeverExposesS3OrFileDataIdentifiersForProposalResults() {
        var fieldNames = java.util.Arrays.stream(
                AttachmentSearchResultResponse.class.getRecordComponents()
        ).map(java.lang.reflect.RecordComponent::getName).toList();

        assertThat(fieldNames)
                .noneMatch(name -> name.toLowerCase().contains("bucket"))
                .noneMatch(name -> name.toLowerCase().contains("objectkey"))
                .noneMatch(name -> name.toLowerCase().contains("filedataid"))
                .noneMatch(name -> name.toLowerCase().contains("checksum"))
                .noneMatch(name -> name.toLowerCase().contains("errormessage"))
                .noneMatch(name -> name.toLowerCase().contains("storagepath"));
    }

    @Test
    void neverInvokesAwardArchiveServiceForAProposalOnlySearch() {
        when(repository.countSearchProposalAttachments(
                anyString(), anyString(), eq(7125L), isNull(), eq("all")
        )).thenReturn(0L);
        when(repository.searchProposalAttachments(
                anyString(), anyString(), eq(7125L), isNull(), eq("all"), anyString(), anyInt(), anyInt()
        )).thenReturn(List.of());

        service.searchAttachments(
                "PROPOSAL", null, null, null, "7125", null, null, null, "all", 0, 25
        );

        verify(awardArchiveService, never()).searchAttachments(
                anyString(), anyString(), anyString(), anyString(), anyString(), anyString(), anyInt(), anyInt()
        );
    }

    // --- Negotiation search ---

    private static final AttachmentSearchRow NEGOTIATION_ROW = new AttachmentSearchRow(
            374L, "231427", null, "JANE NEGOTIATOR", null,
            "231427", 501L, null, "agreement.pdf",
            null, null, 42_000L, "application/pdf",
            "ARCHIVED", true, true
    );

    @Test
    void negotiationNumberExactSearchUsesTheDocumentNumberColumn() {
        when(repository.countSearchNegotiationAttachments(
                eq("231427"), anyString(), isNull(), isNull(), eq("all")
        )).thenReturn(1L);
        when(repository.searchNegotiationAttachments(
                eq("231427"), anyString(), isNull(), isNull(), eq("all"), anyString(), eq(25), eq(0)
        )).thenReturn(List.of(NEGOTIATION_ROW));

        PageResponse<AttachmentSearchResultResponse> result = service.searchAttachments(
                "NEGOTIATION", "231427", null, null, null, null, null, null, "all", 0, 25
        );

        assertThat(result.content()).hasSize(1);
        AttachmentSearchResultResponse row = result.content().get(0);
        assertThat(row.recordType()).isEqualTo("NEGOTIATION");
        assertThat(row.parentNumber()).isEqualTo("231427");
        assertThat(row.fileId()).isNull();
        verifyNoInteractions(awardArchiveService);
    }

    @Test
    void negotiationIdExactSearch() {
        when(repository.countSearchNegotiationAttachments(
                anyString(), anyString(), eq(374L), isNull(), eq("all")
        )).thenReturn(1L);
        when(repository.searchNegotiationAttachments(
                anyString(), anyString(), eq(374L), isNull(), eq("all"), anyString(), eq(25), eq(0)
        )).thenReturn(List.of(NEGOTIATION_ROW));

        PageResponse<AttachmentSearchResultResponse> result = service.searchAttachments(
                "NEGOTIATION", null, null, null, "374", null, null, null, "all", 0, 25
        );

        assertThat(result.content()).hasSize(1);
        assertThat(result.content().get(0).parentId()).isEqualTo(374L);
    }

    @Test
    void negotiationAttachmentIdExactSearchWithRecordTypeNegotiation() {
        when(repository.countSearchNegotiationAttachments(
                anyString(), anyString(), isNull(), eq(501L), eq("all")
        )).thenReturn(1L);
        when(repository.searchNegotiationAttachments(
                anyString(), anyString(), isNull(), eq(501L), eq("all"), anyString(), eq(25), eq(0)
        )).thenReturn(List.of(NEGOTIATION_ROW));

        PageResponse<AttachmentSearchResultResponse> result = service.searchAttachments(
                "NEGOTIATION", null, null, null, null, null, "501", null, "all", 0, 25
        );

        assertThat(result.content()).hasSize(1);
        assertThat(result.content().get(0).attachmentId()).isEqualTo(501L);
    }

    @Test
    void negotiationRejectsAllBlankIdentifiers() {
        assertThatThrownBy(() ->
                service.searchAttachments(
                        "NEGOTIATION", "", null, "  ", null, null, null, null, "all", 0, 25
                )
        )
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("At least one identifier");

        verifyNoInteractions(repository);
    }

    @Test
    void fileIdWithRecordTypeNegotiationIsRejected() {
        assertThatThrownBy(() ->
                service.searchAttachments(
                        "NEGOTIATION", "231427", null, null, null, null, null, "5001", "all", 0, 25
                )
        )
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("fileId")
                .hasMessageContaining("NEGOTIATION");

        verifyNoInteractions(repository, awardArchiveService);
    }

    @Test
    void neverInvokesAwardArchiveServiceForANegotiationOnlySearch() {
        when(repository.countSearchNegotiationAttachments(
                anyString(), anyString(), eq(374L), isNull(), eq("all")
        )).thenReturn(0L);
        when(repository.searchNegotiationAttachments(
                anyString(), anyString(), eq(374L), isNull(), eq("all"), anyString(), anyInt(), anyInt()
        )).thenReturn(List.of());

        service.searchAttachments(
                "NEGOTIATION", null, null, null, "374", null, null, null, "all", 0, 25
        );

        verify(awardArchiveService, never()).searchAttachments(
                anyString(), anyString(), anyString(), anyString(), anyString(), anyString(), anyInt(), anyInt()
        );
    }

    @Test
    void negotiationHistoricalVersionFilterMatchesNoRowsSinceThereIsNoVersionChain() {
        when(repository.countSearchNegotiationAttachments(
                anyString(), anyString(), eq(374L), isNull(), eq("historical")
        )).thenReturn(0L);
        when(repository.searchNegotiationAttachments(
                anyString(), anyString(), eq(374L), isNull(), eq("historical"), anyString(), anyInt(), anyInt()
        )).thenReturn(List.of());

        PageResponse<AttachmentSearchResultResponse> result = service.searchAttachments(
                "NEGOTIATION", null, null, null, "374", null, null, null, "historical", 0, 25
        );

        assertThat(result.totalElements()).isZero();
    }

    // --- Negotiation availability mapping (three-value vocabulary, no "pending" state) ---

    @Test
    void negotiationMissingNeverMapsToPendingUpload() {
        AttachmentSearchRow missing = new AttachmentSearchRow(
                374L, "231427", null, "JANE NEGOTIATOR", null,
                "231427", 502L, null, "unavailable.pdf",
                null, null, 1000L, "application/pdf",
                "MISSING", false, true
        );
        when(repository.countSearchNegotiationAttachments(
                anyString(), anyString(), eq(374L), isNull(), eq("all")
        )).thenReturn(1L);
        when(repository.searchNegotiationAttachments(
                anyString(), anyString(), eq(374L), isNull(), eq("all"), anyString(), anyInt(), anyInt()
        )).thenReturn(List.of(missing));

        PageResponse<AttachmentSearchResultResponse> result = service.searchAttachments(
                "NEGOTIATION", null, null, null, "374", null, null, null, "all", 0, 25
        );

        assertThat(result.content().get(0).availabilityStatus())
                .isEqualTo("Source file unavailable");
    }

    @Test
    void negotiationFailedMapsToFailed() {
        AttachmentSearchRow failed = new AttachmentSearchRow(
                374L, "231427", null, "JANE NEGOTIATOR", null,
                "231427", 503L, null, "failed.pdf",
                null, null, 1000L, "application/pdf",
                "FAILED", false, true
        );
        when(repository.countSearchNegotiationAttachments(
                anyString(), anyString(), eq(374L), isNull(), eq("all")
        )).thenReturn(1L);
        when(repository.searchNegotiationAttachments(
                anyString(), anyString(), eq(374L), isNull(), eq("all"), anyString(), anyInt(), anyInt()
        )).thenReturn(List.of(failed));

        PageResponse<AttachmentSearchResultResponse> result = service.searchAttachments(
                "NEGOTIATION", null, null, null, "374", null, null, null, "all", 0, 25
        );

        assertThat(result.content().get(0).availabilityStatus()).isEqualTo("Failed");
    }

    // --- Mixed ALL search now spans three domains ---

    @Test
    void mixedAllSearchReturnsAwardProposalAndNegotiationResults() {
        MixedAttachmentSearchRow awardRow = new MixedAttachmentSearchRow(
                "AWARD", 3047454L, "200086-00001", "Award Title", "PI", 165,
                "879423", 9001L, 5001L, "Notice of Award.pdf", "Notice of Award",
                null, 1_800_000L, "application/pdf", "UPLOADED", true, true
        );
        MixedAttachmentSearchRow proposalRow = new MixedAttachmentSearchRow(
                "PROPOSAL", 7125L, "2975", "Proposal Title", "PI", 4,
                "879423", 501508L, null, "Notice.pdf", "Notice",
                null, 1_800_000L, "application/pdf", "UPLOADED", true, true
        );
        MixedAttachmentSearchRow negotiationRow = new MixedAttachmentSearchRow(
                "NEGOTIATION", 374L, "879423", null, "JANE NEGOTIATOR", null,
                "879423", 501L, null, "agreement.pdf", null,
                null, 42_000L, "application/pdf", "ARCHIVED", true, true
        );
        when(repository.countSearchAllAttachments(anyString(), anyString(), eq("all")))
                .thenReturn(3L);
        when(repository.searchAllAttachments(
                anyString(), anyString(), eq("all"), anyString(), eq(25), eq(0)
        )).thenReturn(List.of(awardRow, proposalRow, negotiationRow));

        PageResponse<AttachmentSearchResultResponse> result = service.searchAttachments(
                "ALL", "879423", null, "879423", null, null, null, null, "all", 0, 25
        );

        assertThat(result.content()).hasSize(3);
        assertThat(result.content())
                .extracting(AttachmentSearchResultResponse::recordType)
                .containsExactly("AWARD", "PROPOSAL", "NEGOTIATION");
        verifyNoInteractions(awardArchiveService);
    }
}
