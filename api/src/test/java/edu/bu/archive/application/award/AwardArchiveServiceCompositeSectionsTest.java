package edu.bu.archive.application.award;

import edu.bu.archive.adapter.in.web.dto.PageResponse;
import edu.bu.archive.adapter.in.web.dto.award.AwardAmountHistoryResponse;
import edu.bu.archive.adapter.in.web.dto.award.AwardAttachmentResponse;
import edu.bu.archive.adapter.in.web.dto.award.AwardCommentCategoryResponse;
import edu.bu.archive.adapter.in.web.dto.award.AwardCommentEntryResponse;
import edu.bu.archive.adapter.in.web.dto.award.AwardCommentRow;
import edu.bu.archive.adapter.in.web.dto.award.AwardCommentsResponse;
import edu.bu.archive.adapter.in.web.dto.award.AwardCustomDataResponse;
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
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
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

    // Sponsor Term / Report Term fixture values below are the real,
    // live-verified rows for award_id 2727052 (award_sponsor_term_id
    // 2479163 / award_report_term_id 2727057) - see
    // AWARD_TERMS_DESIGN.md and AwardV1ContractTest's own Terms
    // coverage for the same fixture used end-to-end.

    @Test
    void findTermsGroupsRecipientsUnderTheirOwnReportTerm() {
        AwardSponsorTermResponse sponsorTerm = new AwardSponsorTermResponse(
                2479163L, 370L, "64",
                "Converted Record.  Please refer to sponsor award "
                        + "documentation for any Equipment Approval terms.",
                "6", "Equipment Approval Terms"
        );
        when(repository.findSponsorTerms(3L))
                .thenReturn(List.of(sponsorTerm));
        when(repository.findReportTermRows(3L)).thenReturn(List.of(
                new AwardReportTermRow(
                        2727057L, "43",
                        "Converted Record  - See Sponsor Documentation",
                        "1", "Financial",
                        "5", "As required", null, null,
                        "6", "As Required",
                        "2", "No",
                        null
                )
        ));
        when(repository.findReportTermRecipientRows(3L)).thenReturn(List.of(
                new AwardReportTermRecipientRow(
                        900L, 2727057L, 42L, "34",
                        "Administrative Contact", null, 1
                )
        ));

        AwardTermsResponse terms = service.findTerms(3L);

        assertThat(terms.sponsorTerms()).containsExactly(sponsorTerm);
        assertThat(terms.reportTerms()).hasSize(1);
        assertThat(terms.reportTerms().get(0).recipientCount()).isEqualTo(1);
        assertThat(terms.reportTerms().get(0).recipients()).hasSize(1);
        assertThat(terms.reportTerms().get(0).recipients().get(0).contactId())
                .isEqualTo(42L);
        assertThat(terms.reportTerms().get(0).recipients().get(0)
                .contactTypeDescription())
                .isEqualTo("Administrative Contact");
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

    @Test
    void findTermsReportsZeroRecipientCountWhenThereAreNoRecipients() {
        // Real, live-verified Oracle fact: AWARD_REP_TERMS_RECNT is
        // empty archive-wide as of the 2026-08 staging verification
        // behind this change, so award_id 2727052's own two report
        // terms genuinely have zero recipients each - not a load gap.
        when(repository.findSponsorTerms(3L)).thenReturn(List.of());
        when(repository.findReportTermRows(3L)).thenReturn(List.of(
                new AwardReportTermRow(
                        2727058L, "26", "Standard BU Invoice",
                        "6", "Payment/Invoice",
                        "5", "As required", null, null,
                        "6", "As Required",
                        "2", "No",
                        null
                )
        ));
        when(repository.findReportTermRecipientRows(3L)).thenReturn(List.of());

        AwardTermsResponse terms = service.findTerms(3L);

        assertThat(terms.reportTerms()).hasSize(1);
        assertThat(terms.reportTerms().get(0).recipientCount()).isZero();
        assertThat(terms.reportTerms().get(0).recipients()).isEmpty();
    }

    // --- Custom Data -----------------------------------------------------
    //
    // A separate Award section from Terms above, never merged - see
    // AwardArchiveRepository.findCustomData's header comment. Mirrors
    // ProposalArchiveV1ServiceTest's Custom Data coverage.

    @Test
    void findCustomDataResolvesTheLabelViaTheSharedLookup() {
        AwardCustomDataResponse row = new AwardCustomDataResponse(
                1L, 3L, "100004-00003", 1, 480L, "Submitted Date",
                "ip_submission_date", "Date", null, "08/09/2011",
                LocalDateTime.of(2017, 5, 3, 11, 31, 39), "dhaywood",
                1L, "OBJ-1"
        );
        when(repository.findCustomData(3L)).thenReturn(List.of(row));

        List<AwardCustomDataResponse> result = service.findCustomData(3L);

        assertThat(result).containsExactly(row);
        assertThat(result.get(0).label()).isEqualTo("Submitted Date");
    }

    @Test
    void findCustomDataThrowsNotFoundWhenTheAwardDoesNotExist() {
        when(repository.findAwardNumberForId(999L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.findCustomData(999L))
                .isInstanceOf(NoSuchElementException.class);
    }

    @Test
    void findCustomDataPreservesARowWithNoMatchingLookupRatherThanDroppingIt() {
        // custom_attribute_id has no foreign key (V038/V064) - Oracle
        // can add an attribute this archive hasn't loaded into
        // archive.custom_attribute yet. The row must still come back,
        // with label/name/dataType null, never silently filtered out.
        AwardCustomDataResponse unresolvedRow = new AwardCustomDataResponse(
                999999L, 3L, "100004-00003", 1, 424242L, null, null,
                null, null, "some value", LocalDateTime.now(),
                "dhaywood", null, null
        );
        when(repository.findCustomData(3L)).thenReturn(List.of(unresolvedRow));

        List<AwardCustomDataResponse> result = service.findCustomData(3L);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).label()).isNull();
        assertThat(result.get(0).value()).isEqualTo("some value");
    }

    @Test
    void findCustomDataPreservesARealBlankValueDistinctFromNoRowAtAll() {
        AwardCustomDataResponse blankRow = new AwardCustomDataResponse(
                1495997L, 3L, "100004-00003", 1, 1209L, "Opportunity Title",
                "OppTitle", "String", null, null,
                LocalDateTime.of(2020, 3, 6, 15, 40, 37), "dhaywood",
                1L, "OBJ-2"
        );
        when(repository.findCustomData(3L)).thenReturn(List.of(blankRow));

        List<AwardCustomDataResponse> result = service.findCustomData(3L);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).value()).isNull();
        assertThat(result.get(0).label()).isEqualTo("Opportunity Title");
    }

    @Test
    void findCustomDataNeverCombinesRowsAcrossSiblingVersions() {
        // Version scoping proof: the service must query by the exact
        // awardId only - never resolve to awardNumber and fan out
        // across the whole family. Real fixture Award 204713-00117 has
        // 7 sibling versions (award_id 2673287..3160098) - combining
        // them would silently merge unrelated versions' data.
        AwardCustomDataResponse thisVersionOnly = new AwardCustomDataResponse(
                1L, 3160098L, "204713-00117", 7, 100L, "Label", "name",
                "String", null, "v7 value", LocalDateTime.now(),
                "dhaywood", 1L, "OBJ-3"
        );
        when(repository.findAwardNumberForId(3160098L))
                .thenReturn(Optional.of("204713-00117"));
        when(repository.findCustomData(3160098L))
                .thenReturn(List.of(thisVersionOnly));

        List<AwardCustomDataResponse> result = service.findCustomData(3160098L);

        assertThat(result).containsExactly(thisVersionOnly);
        verify(repository).findCustomData(3160098L);
        verify(repository, never()).findCustomData(2673287L);
    }

    // --- Comments and Notepad --------------------------------------------

    @Test
    void findCommentsGroupsIntoOneCategoryAndKeepsNotepadSeparate() {
        AwardCommentRow row = new AwardCommentRow(
                1L, 3L, 1, "AWD000030001", "2", "General Comments",
                "A version-scoped comment.",
                LocalDateTime.of(2021, 1, 1, 0, 0), "jsmith"
        );
        AwardNotepadEntryResponse notepadEntry = new AwardNotepadEntryResponse(
                1L, 1, "Kickoff", "A family-wide note.\nSecond line.",
                "N", LocalDateTime.of(2020, 1, 1, 0, 0), "jsmith",
                LocalDateTime.of(2020, 1, 1, 0, 0), "jsmith"
        );
        when(repository.findComments("100004-00003")).thenReturn(List.of(row));
        when(repository.findNotepadEntries("100004-00003"))
                .thenReturn(List.of(notepadEntry));

        AwardCommentsResponse comments = service.findComments(3L);

        assertThat(comments.commentCategories()).hasSize(1);
        AwardCommentCategoryResponse category = comments.commentCategories().get(0);
        assertThat(category.commentTypeCode()).isEqualTo("2");
        assertThat(category.commentTypeDescription()).isEqualTo("General Comments");
        assertThat(category.current().commentText())
                .isEqualTo("A version-scoped comment.");
        assertThat(category.history()).hasSize(1);
        assertThat(comments.notepadEntries()).containsExactly(notepadEntry);
    }

    @Test
    void findCommentsReturnsEmptyListsRatherThanNull() {
        when(repository.findComments("100004-00003")).thenReturn(List.of());
        when(repository.findNotepadEntries("100004-00003"))
                .thenReturn(List.of());

        AwardCommentsResponse comments = service.findComments(3L);

        assertThat(comments.commentCategories()).isEmpty();
        assertThat(comments.notepadEntries()).isEmpty();
    }

    @Test
    void findCommentsShowsNoCommentRecordedWhenACommentTypeHasNoRealRows() {
        // The LEFT JOIN in AwardArchiveRepository.findComments returns one
        // all-null-except-type row for a screen_flag='Y' comment type this
        // Award family has never used - award_comment_id is null.
        AwardCommentRow noCommentsOfThisType = new AwardCommentRow(
                null, null, null, null, "3", "Fiscal Report Comments",
                null, null, null
        );
        when(repository.findComments("100004-00003"))
                .thenReturn(List.of(noCommentsOfThisType));
        when(repository.findNotepadEntries("100004-00003"))
                .thenReturn(List.of());

        AwardCommentsResponse comments = service.findComments(3L);

        assertThat(comments.commentCategories()).hasSize(1);
        AwardCommentCategoryResponse category = comments.commentCategories().get(0);
        assertThat(category.commentTypeCode()).isEqualTo("3");
        assertThat(category.commentTypeDescription())
                .isEqualTo("Fiscal Report Comments");
        assertThat(category.current()).isNull();
        assertThat(category.history()).isEmpty();
    }

    @Test
    void findCommentsCollapsesOnlyTrulyConsecutiveIdenticalText() {
        AwardCommentRow newest = new AwardCommentRow(
                3L, 3L, 3, "AWD000030003", "2", "General Comments",
                "Same text.",
                LocalDateTime.of(2022, 1, 1, 0, 0), "jsmith"
        );
        AwardCommentRow middleIdenticalToNewest = new AwardCommentRow(
                2L, 3L, 2, "AWD000030002", "2", "General Comments",
                "Same text.",
                LocalDateTime.of(2021, 1, 1, 0, 0), "jsmith"
        );
        AwardCommentRow oldestDifferent = new AwardCommentRow(
                1L, 3L, 1, "AWD000030001", "2", "General Comments",
                "Different text.",
                LocalDateTime.of(2020, 1, 1, 0, 0), "jsmith"
        );
        when(repository.findComments("100004-00003")).thenReturn(
                List.of(newest, middleIdenticalToNewest, oldestDifferent)
        );
        when(repository.findNotepadEntries("100004-00003"))
                .thenReturn(List.of());

        AwardCommentsResponse comments = service.findComments(3L);

        AwardCommentCategoryResponse category = comments.commentCategories().get(0);
        // The consecutive duplicate (middleIdenticalToNewest) collapses into
        // the OLDER of the pair - matching real Kuali's
        // AwardCommentServiceImpl.filterAwardComment, which walks oldest-
        // to-newest and keeps the version where the text was first
        // introduced, not the latest copy-forward repetition (confirmed
        // against real Award 100330-00001 data - see
        // findCommentsPreservesTheRealAward100330GeneralCommentsHistory).
        assertThat(category.history()).hasSize(2);
        assertThat(category.history().get(0).awardCommentId()).isEqualTo(2L);
        assertThat(category.history().get(1).awardCommentId()).isEqualTo(1L);
    }

    @Test
    void findCommentsPreservesTheRealAward100330GeneralCommentsHistory() {
        // Real, live-verified data for Award 100330-00001 / award_id
        // 3038231 (fetched directly from the dev archive database): 12
        // award_comment rows for comment_type_code "2" (General Comments),
        // sequence 1 through 12. The "*Converted Record" text is
        // copy-forward-identical across sequence 4 through 12 (2014-03-11
        // through 2021-09-20) - real Kuali's AwardCommentServiceImpl
        // attributes that whole run to its EARLIEST occurrence (sequence
        // 4, 2014-03-11), not the latest copy, matching this test's
        // expected "current" entry. Sequence 2 and 3 are likewise
        // identical to each other (no "*Converted Record" suffix) and
        // collapse to sequence 2, the earlier of the pair. Sequence 1
        // (2011) is unrelated and always its own entry.
        when(repository.findAwardNumberForId(3038231L))
                .thenReturn(Optional.of("100330-00001"));

        String convertedRecordText =
                "This action: Modification No.7 dated 2/27/2014 extends "
                        + "period of performance to 2/28/2014. All other "
                        + "terms and conditions remain unchanged and in "
                        + "effect. \n*Converted Record.  See original "
                        + "sponsor documentation for terms and conditions.";
        String noSuffixText =
                "This action: Modification No.7 dated 2/27/2014 extends "
                        + "period of performance to 2/28/2014. All other "
                        + "terms and conditions remain unchanged and in "
                        + "effect.";
        String mod5Text =
                "MODIFICATION NO.5 APPROVES NO-COST EXTENSION TO 2/29/12. "
                        + "SUBCONTRACT UNDER PRIME NSF COOPERATIVE "
                        + "AGREEMENT NO. HRD-0450339. SEE SOURC E 6377-7 "
                        + "FOR PARTICIPANT SUPPORT COSTS ASSOCIATED WITH "
                        + "THIS PROJECT. TOTAL FUNDING ALLOCATED TO DATE: "
                        + "$673,710.     ";

        List<AwardCommentRow> realRowsNewestFirst = List.of(
                row(1663054L, 3038231L, 12, "876895", convertedRecordText,
                        LocalDateTime.of(2021, 9, 20, 13, 52, 59), "mlmacd"),
                row(1662945L, 3037985L, 11, "876865", convertedRecordText,
                        LocalDateTime.of(2021, 9, 20, 13, 47, 36), "mlmacd"),
                row(1654620L, 3025758L, 10, "874105", convertedRecordText,
                        LocalDateTime.of(2021, 9, 8, 17, 38, 31), "mlmacd"),
                row(572940L, 1415859L, 9, "391375", convertedRecordText,
                        LocalDateTime.of(2015, 10, 20, 10, 59, 47), "brycekel"),
                row(544658L, 1323390L, 8, "378047", convertedRecordText,
                        LocalDateTime.of(2015, 8, 11, 10, 35, 11), "brycekel"),
                row(522314L, 1284132L, 7, "367288", convertedRecordText,
                        LocalDateTime.of(2015, 6, 11, 14, 44, 57), "zaccaria"),
                row(448446L, 1153439L, 6, "332546", convertedRecordText,
                        LocalDateTime.of(2014, 10, 23, 10, 13, 44), "zaccaria"),
                row(437729L, 1133724L, 5, "328586", convertedRecordText,
                        LocalDateTime.of(2014, 9, 29, 14, 25, 50), "brycekel"),
                row(347503L, 877063L, 4, "281754", convertedRecordText,
                        LocalDateTime.of(2014, 3, 11, 15, 55, 34), "prokorym"),
                row(347037L, 875833L, 3, "281498", noSuffixText,
                        LocalDateTime.of(2014, 3, 11, 12, 44, 10), "acolon"),
                row(346986L, 875677L, 2, "281462", noSuffixText,
                        LocalDateTime.of(2014, 3, 11, 12, 30, 46), "acolon"),
                row(448L, 224L, 1, "224", mod5Text,
                        LocalDateTime.of(2011, 6, 24, 16, 49, 46), "kcrm")
        );
        when(repository.findComments("100330-00001"))
                .thenReturn(realRowsNewestFirst);
        when(repository.findNotepadEntries("100330-00001"))
                .thenReturn(List.of());

        AwardCommentsResponse comments = service.findComments(3038231L);

        assertThat(comments.commentCategories()).hasSize(1);
        AwardCommentCategoryResponse general = comments.commentCategories().get(0);
        assertThat(general.commentTypeCode()).isEqualTo("2");
        assertThat(general.history()).hasSize(3);

        AwardCommentEntryResponse current = general.current();
        assertThat(current.awardCommentId()).isEqualTo(347503L);
        assertThat(current.awardId()).isEqualTo(877063L);
        assertThat(current.sequenceNumber()).isEqualTo(4);
        assertThat(current.commentText()).contains("*Converted Record");

        AwardCommentEntryResponse noSuffixEntry = general.history().get(1);
        assertThat(noSuffixEntry.awardCommentId()).isEqualTo(346986L);
        assertThat(noSuffixEntry.awardId()).isEqualTo(875677L);
        assertThat(noSuffixEntry.sequenceNumber()).isEqualTo(2);
        assertThat(noSuffixEntry.commentText()).isEqualTo(noSuffixText);

        AwardCommentEntryResponse mod5Entry = general.history().get(2);
        assertThat(mod5Entry.awardCommentId()).isEqualTo(448L);
        assertThat(mod5Entry.awardId()).isEqualTo(224L);
        assertThat(mod5Entry.sequenceNumber()).isEqualTo(1);
        assertThat(mod5Entry.commentText()).contains("MODIFICATION NO.5");
    }

    private static AwardCommentRow row(
            long awardCommentId,
            long awardId,
            int sequenceNumber,
            String workflowDocumentNumber,
            String comments,
            LocalDateTime updateTimestamp,
            String updateUser
    ) {
        return new AwardCommentRow(
                awardCommentId, awardId, sequenceNumber, workflowDocumentNumber,
                "2", "General Comments", comments, updateTimestamp, updateUser
        );
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
    void findAttachmentsBuildsAPaginatedResponse() {
        AwardAttachmentResponse attachment = new AwardAttachmentResponse(
                500L, "100004-00003", 1, "budget.pdf", "application/pdf",
                "Budget justification", "BUD", "COMPLETE", 1024L,
                "UPLOADED", true, LocalDateTime.of(2021, 1, 1, 0, 0)
        );
        when(repository.countAttachments(3L)).thenReturn(1L);
        when(repository.findAttachments(3L, 25, 0))
                .thenReturn(List.of(attachment));

        PageResponse<AwardAttachmentResponse> page =
                service.findAttachments(3L, 0, 25);

        assertThat(page.content()).containsExactly(attachment);
        assertThat(page.totalElements()).isEqualTo(1L);
        assertThat(page.first()).isTrue();
        assertThat(page.last()).isTrue();
    }

    @Test
    void findAttachmentsAppliesTheSamePaginationClampingAsOtherLists() {
        when(repository.countAttachments(3L)).thenReturn(0L);
        when(repository.findAttachments(3L, 100, 0)).thenReturn(List.of());

        PageResponse<AwardAttachmentResponse> page =
                service.findAttachments(3L, -1, 500);

        assertThat(page.page()).isZero();
        assertThat(page.size()).isEqualTo(100);
        verify(repository).findAttachments(3L, 100, 0);
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
