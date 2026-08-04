package edu.bu.archive.adapter.in.web;

import edu.bu.archive.adapter.in.web.dto.PageResponse;
import edu.bu.archive.adapter.in.web.dto.award.AwardAmountHistoryResponse;
import edu.bu.archive.adapter.in.web.dto.award.AwardAttachmentResponse;
import edu.bu.archive.adapter.in.web.dto.award.AwardCentralAdministrationContactResponse;
import edu.bu.archive.adapter.in.web.dto.award.AwardCommentsResponse;
import edu.bu.archive.adapter.in.web.dto.award.AwardHierarchyNodeResponse;
import edu.bu.archive.adapter.in.web.dto.award.AwardHierarchyResponse;
import edu.bu.archive.adapter.in.web.dto.award.AwardIdentifierResponse;
import edu.bu.archive.adapter.in.web.dto.award.AwardPersonDetailResponse;
import edu.bu.archive.adapter.in.web.dto.award.AwardDocumentNumberMatchResponse;
import edu.bu.archive.adapter.in.web.dto.award.AwardSapTransmissionResponse;
import edu.bu.archive.adapter.in.web.dto.award.AwardSearchResponse;
import edu.bu.archive.adapter.in.web.dto.award.AwardSearchResultResponse;
import edu.bu.archive.adapter.in.web.dto.award.AwardSummaryResponse;
import edu.bu.archive.adapter.in.web.dto.award.AwardTermsResponse;
import edu.bu.archive.adapter.in.web.dto.award.AwardUnitContactResponse;
import edu.bu.archive.adapter.in.web.dto.award.AwardUnitDetailsResponse;
import edu.bu.archive.adapter.in.web.dto.award.AwardVersionSummaryResponse;
import edu.bu.archive.application.award.AwardArchiveService;
import edu.bu.archive.application.award.AwardAttachmentDownload;
import edu.bu.archive.application.award.AwardContactService;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.NoSuchElementException;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AwardV1ControllerTest {

    private AwardArchiveService service;
    private AwardContactService contactService;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        service = mock(AwardArchiveService.class);
        contactService = mock(AwardContactService.class);
        AwardV1Controller controller = new AwardV1Controller(service, contactService);
        mockMvc = MockMvcBuilders
                .standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    void searchIsRoutedUnderTheV1Prefix() throws Exception {
        AwardSearchResultResponse result = new AwardSearchResultResponse(
                3L, "100004-00003", 1, "Title", "Approved Award",
                "MICHAEL MCCLEAN", "Brown University",
                "SPH ENVIRONMENTAL HEALTH", BigDecimal.TEN, null, null
        );
        PageResponse<AwardSearchResultResponse> page = new PageResponse<>(
                List.of(result), 1, 10, 1L, 1, false, true
        );
        when(service.search("cancer", 1, 10))
                .thenReturn(new AwardSearchResponse(null, page));

        mockMvc.perform(
                        get("/api/v1/awards/search")
                                .param("q", "cancer")
                                .param("page", "1")
                                .param("size", "10")
                )
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.exactDocumentMatch").doesNotExist())
                .andExpect(jsonPath("$.results.page").value(1))
                .andExpect(jsonPath("$.results.size").value(10))
                .andExpect(
                        jsonPath("$.results.content[0].awardNumber")
                                .value("100004-00003")
                );

        verify(service).search("cancer", 1, 10);
    }

    @Test
    void searchDefaultsPageAndSizeWhenOmitted() throws Exception {
        when(service.search(null, 0, 25)).thenReturn(new AwardSearchResponse(
                null,
                new PageResponse<>(List.of(), 0, 25, 0L, 0, true, true)
        ));

        mockMvc.perform(get("/api/v1/awards/search"))
                .andExpect(status().isOk());

        verify(service).search(null, 0, 25);
    }

    @Test
    void searchReturnsTheExactWorkflowDocumentMatchWhenPresent()
            throws Exception {
        AwardDocumentNumberMatchResponse match =
                new AwardDocumentNumberMatchResponse(
                        1135067L, "100567-00001", 6, "328797", "Award",
                        "Title", "Approved Award"
                );
        when(service.search("328797", 0, 25)).thenReturn(
                new AwardSearchResponse(
                        match,
                        new PageResponse<>(List.of(), 0, 25, 0L, 0, true, true)
                )
        );

        mockMvc.perform(
                        get("/api/v1/awards/search").param("q", "328797")
                )
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.exactDocumentMatch.awardId").value(1135067))
                .andExpect(
                        jsonPath("$.exactDocumentMatch.sequenceNumber")
                                .value(6)
                )
                .andExpect(
                        jsonPath("$.exactDocumentMatch.workflowDocumentNumber")
                                .value("328797")
                );

        verify(service).search("328797", 0, 25);
    }

    @Test
    void resolveByNumberReturnsTheCurrentAwardIdentifiers() throws Exception {
        when(service.resolveIdentifier("200268-00001")).thenReturn(
                new AwardIdentifierResponse(148155L, "200268-00001", 1)
        );

        mockMvc.perform(get("/api/v1/awards/by-number/200268-00001"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.awardId").value(148155))
                .andExpect(jsonPath("$.awardNumber").value("200268-00001"))
                .andExpect(jsonPath("$.sequenceNumber").value(1));

        verify(service).resolveIdentifier("200268-00001");
    }

    @Test
    void resolveByNumberPropagatesNotFoundWithConsistentErrorShape()
            throws Exception {
        when(service.resolveIdentifier("NO-SUCH-AWARD")).thenThrow(
                new NoSuchElementException("Award not found: NO-SUCH-AWARD")
        );

        mockMvc.perform(get("/api/v1/awards/by-number/NO-SUCH-AWARD"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test
    void hierarchyIsRoutedUnderTheV1Prefix() throws Exception {
        AwardHierarchyNodeResponse root = new AwardHierarchyNodeResponse(
                "100004-00001", 1L, 9, null, true, "Title", "Closed",
                "MICHAEL MCCLEAN", "Brown University",
                "SPH ENVIRONMENTAL HEALTH", BigDecimal.TEN, List.of()
        );
        AwardHierarchyResponse hierarchy = new AwardHierarchyResponse(
                "100004-00001", "100004-00001", root,
                List.of("100004-00001")
        );
        when(service.findHierarchy("100004-00001"))
                .thenReturn(hierarchy);

        mockMvc.perform(get("/api/v1/awards/100004-00001/hierarchy"))
                .andExpect(status().isOk())
                .andExpect(
                        jsonPath("$.rootAwardNumber")
                                .value("100004-00001")
                );

        verify(service).findHierarchy("100004-00001");
    }

    @Test
    void hierarchyPropagatesNotFoundWithConsistentErrorShape() throws Exception {
        when(service.findHierarchy("NO-SUCH-AWARD"))
                .thenThrow(new NoSuchElementException(
                        "Award not found: NO-SUCH-AWARD"
                ));

        mockMvc.perform(get("/api/v1/awards/NO-SUCH-AWARD/hierarchy"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"))
                .andExpect(
                        jsonPath("$.message")
                                .value("Award not found: NO-SUCH-AWARD")
                )
                .andExpect(
                        jsonPath("$.path")
                                .value("/api/v1/awards/NO-SUCH-AWARD/hierarchy")
                )
                .andExpect(jsonPath("$.correlationId").exists());
    }

    @Test
    void summaryIsRoutedUnderTheV1Prefix() throws Exception {
        AwardSummaryResponse summary = new AwardSummaryResponse(
                3L, "100004-00003", 1, "Title", "Approved Award",
                "Brown University", null, "MICHAEL MCCLEAN",
                "SPH ENVIRONMENTAL HEALTH", null, null, null, null,
                BigDecimal.TEN, BigDecimal.TEN, "1", "Cost reimbursement",
                "28", "Invoice", null, null
        );
        when(service.findSummary(3L)).thenReturn(summary);

        mockMvc.perform(get("/api/v1/awards/3/summary"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.awardNumber")
                        .value("100004-00003"));

        verify(service).findSummary(3L);
    }

    @Test
    void summaryPropagatesNotFoundAsAnHttp404() throws Exception {
        when(service.findSummary(999L))
                .thenThrow(new NoSuchElementException(
                        "Award not found: 999"
                ));

        mockMvc.perform(get("/api/v1/awards/999/summary"))
                .andExpect(status().isNotFound());
    }

    @Test
    void versionsIsPaginatedLikeSearch() throws Exception {
        AwardVersionSummaryResponse version =
                new AwardVersionSummaryResponse(
                        3L, "100004-00003", 1, "Approved Award",
                        "12", "Converted Record", null, null, null, null, true
                );
        PageResponse<AwardVersionSummaryResponse> page = new PageResponse<>(
                List.of(version), 0, 50, 1L, 1, true, true
        );
        when(service.findVersions(3L, 0, 50)).thenReturn(page);

        mockMvc.perform(get("/api/v1/awards/3/versions"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.size").value(50))
                .andExpect(jsonPath("$.content[0].awardNumber")
                        .value("100004-00003"))
                .andExpect(jsonPath("$.content[0].sequenceNumber").value(1));

        verify(service).findVersions(3L, 0, 50);
    }

    @Test
    void versionsPropagatesNotFoundAsAnHttp404() throws Exception {
        when(service.findVersions(999L, 0, 50))
                .thenThrow(new NoSuchElementException(
                        "Award not found: 999"
                ));

        mockMvc.perform(get("/api/v1/awards/999/versions"))
                .andExpect(status().isNotFound());
    }

    @Test
    void peopleIsRoutedUnderTheV1Prefix() throws Exception {
        AwardPersonDetailResponse person = new AwardPersonDetailResponse(
                10L, "P100", "MICHAEL MCCLEAN", "PI", "PI", true,
                BigDecimal.ONE, BigDecimal.ONE, null, BigDecimal.ONE,
                List.of(), List.of()
        );
        when(service.findPeople(3L)).thenReturn(List.of(person));

        mockMvc.perform(get("/api/v1/awards/3/people"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].fullName")
                        .value("MICHAEL MCCLEAN"))
                .andExpect(jsonPath("$[0].leadPrincipalInvestigator")
                        .value(true));

        verify(service).findPeople(3L);
    }

    @Test
    void peoplePropagatesNotFoundAsAnHttp404() throws Exception {
        when(service.findPeople(999L))
                .thenThrow(new NoSuchElementException("Award not found: 999"));

        mockMvc.perform(get("/api/v1/awards/999/people"))
                .andExpect(status().isNotFound());
    }

    @Test
    void unitDetailsIsRoutedUnderTheV1Prefix() throws Exception {
        AwardUnitDetailsResponse unit = new AwardUnitDetailsResponse(
                "1203250000", "CAS SPACE PHYSICS", "1200000000",
                "COLLEGE OF ARTS & SCIENCES (CAS)", "1", true
        );
        when(contactService.findUnitDetails(985585L)).thenReturn(unit);

        mockMvc.perform(get("/api/v1/awards/985585/unit-details"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.unitNumber").value("1203250000"))
                .andExpect(jsonPath("$.unitName").value("CAS SPACE PHYSICS"))
                .andExpect(jsonPath("$.leadUnit").value(true));

        verify(contactService).findUnitDetails(985585L);
    }

    @Test
    void unitDetailsPropagatesNotFoundAsAnHttp404() throws Exception {
        when(contactService.findUnitDetails(999L))
                .thenThrow(new NoSuchElementException("Award not found: 999"));

        mockMvc.perform(get("/api/v1/awards/999/unit-details"))
                .andExpect(status().isNotFound());
    }

    @Test
    void unitContactsIsRoutedUnderTheV1Prefix() throws Exception {
        AwardUnitContactResponse erin = new AwardUnitContactResponse(
                "U17311007", "ERIN REYNOLDS",
                "Post-Award - Department Administrator", "1203250000", true,
                "EREYNOLD@BU.EDU", "617-358-0603"
        );
        when(contactService.findUnitContacts(985585L)).thenReturn(List.of(erin));

        mockMvc.perform(get("/api/v1/awards/985585/unit-contacts"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].fullName").value("ERIN REYNOLDS"))
                .andExpect(jsonPath("$[0].leadUnit").value(true));

        verify(contactService).findUnitContacts(985585L);
    }

    @Test
    void unitContactsPropagatesNotFoundAsAnHttp404() throws Exception {
        when(contactService.findUnitContacts(999L))
                .thenThrow(new NoSuchElementException("Award not found: 999"));

        mockMvc.perform(get("/api/v1/awards/999/unit-contacts"))
                .andExpect(status().isNotFound());
    }

    @Test
    void sponsorContactsIsRoutedUnderTheV1PrefixAndCanBeEmpty() throws Exception {
        when(contactService.findSponsorContacts(985585L)).thenReturn(List.of());

        mockMvc.perform(get("/api/v1/awards/985585/sponsor-contacts"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$").isEmpty());

        verify(contactService).findSponsorContacts(985585L);
    }

    @Test
    void sponsorContactsPropagatesNotFoundAsAnHttp404() throws Exception {
        when(contactService.findSponsorContacts(999L))
                .thenThrow(new NoSuchElementException("Award not found: 999"));

        mockMvc.perform(get("/api/v1/awards/999/sponsor-contacts"))
                .andExpect(status().isNotFound());
    }

    @Test
    void centralAdministrationContactsIsRoutedUnderTheV1Prefix() throws Exception {
        AwardCentralAdministrationContactResponse nancy =
                new AwardCentralAdministrationContactResponse(
                        "U44984650", "NANCY SCHINDELE", "PAFO Administrator",
                        "NANCYSCH@BU.EDU", "617-358-5117"
                );
        AwardCentralAdministrationContactResponse anthony =
                new AwardCentralAdministrationContactResponse(
                        "U98756203", "ANTHONY J MOY", "OSP Administrator",
                        "TMOY@BU.EDU", "617-353-4365"
                );
        when(contactService.findCentralAdministrationContacts(985585L))
                .thenReturn(List.of(anthony, nancy));

        mockMvc.perform(
                        get("/api/v1/awards/985585/central-administration-contacts")
                )
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].fullName").value("ANTHONY J MOY"))
                .andExpect(jsonPath("$[0].projectRole")
                        .value("OSP Administrator"))
                .andExpect(jsonPath("$[1].fullName").value("NANCY SCHINDELE"))
                .andExpect(jsonPath("$[1].projectRole")
                        .value("PAFO Administrator"));

        verify(contactService).findCentralAdministrationContacts(985585L);
    }

    @Test
    void centralAdministrationContactsPropagatesNotFoundAsAnHttp404()
            throws Exception {
        when(contactService.findCentralAdministrationContacts(999L))
                .thenThrow(new NoSuchElementException("Award not found: 999"));

        mockMvc.perform(
                        get("/api/v1/awards/999/central-administration-contacts")
                )
                .andExpect(status().isNotFound());
    }

    @Test
    void amountsIsPaginatedLikeVersions() throws Exception {
        AwardAmountHistoryResponse amount = new AwardAmountHistoryResponse(
                1L, 3L, "100004-00003", 1, BigDecimal.TEN, BigDecimal.ONE,
                BigDecimal.TEN, null, null, null, null, BigDecimal.TEN,
                null, "DOC-1", 1L
        );
        PageResponse<AwardAmountHistoryResponse> page = new PageResponse<>(
                List.of(amount), 0, 50, 1L, 1, true, true
        );
        when(service.findAmounts(3L, 0, 50)).thenReturn(page);

        mockMvc.perform(get("/api/v1/awards/3/amounts"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].awardNumber")
                        .value("100004-00003"));

        verify(service).findAmounts(3L, 0, 50);
    }

    @Test
    void amountsPropagatesNotFoundAsAnHttp404() throws Exception {
        when(service.findAmounts(999L, 0, 50))
                .thenThrow(new NoSuchElementException("Award not found: 999"));

        mockMvc.perform(get("/api/v1/awards/999/amounts"))
                .andExpect(status().isNotFound());
    }

    @Test
    void termsIsRoutedUnderTheV1Prefix() throws Exception {
        AwardTermsResponse terms = new AwardTermsResponse(List.of(), List.of());
        when(service.findTerms(3L)).thenReturn(terms);

        mockMvc.perform(get("/api/v1/awards/3/terms"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sponsorTerms").isArray())
                .andExpect(jsonPath("$.reportTerms").isArray());

        verify(service).findTerms(3L);
    }

    @Test
    void termsPropagatesNotFoundAsAnHttp404() throws Exception {
        when(service.findTerms(999L))
                .thenThrow(new NoSuchElementException("Award not found: 999"));

        mockMvc.perform(get("/api/v1/awards/999/terms"))
                .andExpect(status().isNotFound());
    }

    @Test
    void commentsIsRoutedUnderTheV1Prefix() throws Exception {
        AwardCommentsResponse comments =
                new AwardCommentsResponse(List.of(), List.of());
        when(service.findComments(3L)).thenReturn(comments);

        mockMvc.perform(get("/api/v1/awards/3/comments"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.commentCategories").isArray())
                .andExpect(jsonPath("$.notepadEntries").isArray());

        verify(service).findComments(3L);
    }

    @Test
    void commentsPropagatesNotFoundAsAnHttp404() throws Exception {
        when(service.findComments(999L))
                .thenThrow(new NoSuchElementException("Award not found: 999"));

        mockMvc.perform(get("/api/v1/awards/999/comments"))
                .andExpect(status().isNotFound());
    }

    @Test
    void sapTransmissionsIsPaginated() throws Exception {
        AwardSapTransmissionResponse transmission =
                new AwardSapTransmissionResponse(
                        700L, "100004-00003", 1, "jsmith", "SAP-GW",
                        "Y", true, null, "1", 28, "NIH", "28", "DOC-1",
                        "<xml>sent</xml>", "<xml>returned</xml>", List.of()
                );
        PageResponse<AwardSapTransmissionResponse> page = new PageResponse<>(
                List.of(transmission), 0, 25, 1L, 1, true, true
        );
        when(service.findSapTransmissions(3L, 0, 25)).thenReturn(page);

        mockMvc.perform(get("/api/v1/awards/3/sap-transmissions"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].successful").value(true))
                .andExpect(jsonPath("$.content[0].sentData")
                        .value("<xml>sent</xml>"));

        verify(service).findSapTransmissions(3L, 0, 25);
    }

    @Test
    void sapTransmissionsPropagatesNotFoundAsAnHttp404() throws Exception {
        when(service.findSapTransmissions(999L, 0, 25))
                .thenThrow(new NoSuchElementException("Award not found: 999"));

        mockMvc.perform(get("/api/v1/awards/999/sap-transmissions"))
                .andExpect(status().isNotFound());
    }

    @Test
    void attachmentsIsPaginatedLikeVersions() throws Exception {
        AwardAttachmentResponse attachment = new AwardAttachmentResponse(
                500L, "100004-00003", 1, "budget.pdf", "application/pdf",
                "Budget justification", "BUD", "COMPLETE", 1024L,
                "UPLOADED", true, LocalDateTime.of(2021, 1, 1, 0, 0)
        );
        PageResponse<AwardAttachmentResponse> page = new PageResponse<>(
                List.of(attachment), 0, 25, 1L, 1, true, true
        );
        when(service.findAttachments(3L, 0, 25)).thenReturn(page);

        mockMvc.perform(get("/api/v1/awards/3/attachments"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].fileName").value("budget.pdf"))
                .andExpect(jsonPath("$.content[0].downloadable").value(true));

        verify(service).findAttachments(3L, 0, 25);
    }

    @Test
    void attachmentsPropagatesNotFoundAsAnHttp404() throws Exception {
        when(service.findAttachments(999L, 0, 25))
                .thenThrow(new NoSuchElementException("Award not found: 999"));

        mockMvc.perform(get("/api/v1/awards/999/attachments"))
                .andExpect(status().isNotFound());
    }

    @Test
    void downloadAttachmentStreamsTheBodyWithAContentDispositionHeader()
            throws Exception {
        byte[] content = {1, 2, 3, 4};
        AwardAttachmentDownload download = new AwardAttachmentDownload(
                "budget.pdf", "application/pdf", content.length,
                new ByteArrayInputStream(content)
        );
        when(service.downloadAttachment(3L, 500L)).thenReturn(download);

        mockMvc.perform(get("/api/v1/awards/3/attachments/500/download"))
                .andExpect(status().isOk())
                .andExpect(header().string(
                        "Content-Disposition",
                        org.hamcrest.Matchers.containsString("budget.pdf")
                ));

        verify(service).downloadAttachment(3L, 500L);
    }

    @Test
    void downloadAttachmentPropagatesNotFoundAsAnHttp404() throws Exception {
        when(service.downloadAttachment(3L, 999L))
                .thenThrow(new NoSuchElementException(
                        "Archived attachment not found"
                ));

        mockMvc.perform(
                        get("/api/v1/awards/3/attachments/999/download")
                )
                .andExpect(status().isNotFound());
    }
}
