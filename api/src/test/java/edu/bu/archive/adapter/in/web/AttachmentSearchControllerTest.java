package edu.bu.archive.adapter.in.web;

import edu.bu.archive.adapter.in.web.dto.PageResponse;
import edu.bu.archive.adapter.in.web.dto.attachment.AttachmentSearchResultResponse;
import edu.bu.archive.application.security.AttachmentAuthorizationService;
import edu.bu.archive.application.service.AttachmentSearchService;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AttachmentSearchControllerTest {

    private AttachmentSearchService service;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        service = mock(AttachmentSearchService.class);
        AttachmentSearchController controller = new AttachmentSearchController(
                service, mock(AttachmentAuthorizationService.class)
        );
        mockMvc = MockMvcBuilders
                .standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    void searchByExactAwardNumberReturnsAPageOfResults() throws Exception {
        AttachmentSearchResultResponse result = new AttachmentSearchResultResponse(
                "AWARD", 3047454L, "200086-00001", "Title", "PI NAME", 165,
                "879423", 9001L, 5001L, "Notice of Award.pdf",
                "Notice of Award", null, 1_800_000L, "application/pdf",
                "Available", true, true
        );
        PageResponse<AttachmentSearchResultResponse> page =
                new PageResponse<>(List.of(result), 0, 25, 1L, 1, true, true);
        when(service.searchAttachments(
                null, null, "200086-00001", null, null, null, null, null, "all", 0, 25
        )).thenReturn(page);

        mockMvc.perform(
                        get("/api/v1/attachments/search")
                                .param("awardNumber", "200086-00001")
                )
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].recordType").value("AWARD"))
                .andExpect(jsonPath("$.content[0].parentNumber").value("200086-00001"))
                .andExpect(jsonPath("$.content[0].attachmentId").value(9001))
                .andExpect(jsonPath("$.content[0].availabilityStatus").value("Available"))
                .andExpect(jsonPath("$.content[0].downloadable").value(true));

        verify(service).searchAttachments(
                null, null, "200086-00001", null, null, null, null, null, "all", 0, 25
        );
    }

    @Test
    void searchReturns400NotAServerErrorWhenNoIdentifierIsSupplied() throws Exception {
        when(service.searchAttachments(
                null, null, null, null, null, null, null, null, "all", 0, 25
        )).thenThrow(new IllegalArgumentException(
                "At least one identifier (awardNumber, documentNumber, "
                        + "awardId, attachmentId, or fileId) must be supplied"
        ));

        mockMvc.perform(get("/api/v1/attachments/search"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BAD_REQUEST"));
    }

    @Test
    void searchReturns400NotAServerErrorForAnInvalidNumericAwardId() throws Exception {
        when(service.searchAttachments(
                null, null, null, null, null, "not-a-number", null, null, "all", 0, 25
        )).thenThrow(new IllegalArgumentException(
                "Award ID must be a valid whole number: not-a-number"
        ));

        mockMvc.perform(
                        get("/api/v1/attachments/search")
                                .param("awardId", "not-a-number")
                )
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BAD_REQUEST"));
    }

    @Test
    void searchReturnsAnUnavailableAttachmentWithDownloadDisabledRatherThanHidingIt()
            throws Exception {
        AttachmentSearchResultResponse pending = new AttachmentSearchResultResponse(
                "AWARD", 3047454L, "200086-00001", "Title", "PI NAME", 165,
                "879423", 9002L, 5002L, "Budget.xlsx", "Budget", null,
                42_000L, "application/vnd.ms-excel", "Pending upload",
                false, true
        );
        PageResponse<AttachmentSearchResultResponse> page =
                new PageResponse<>(List.of(pending), 0, 25, 1L, 1, true, true);
        when(service.searchAttachments(
                null, null, "200086-00001", null, null, null, null, null, "all", 0, 25
        )).thenReturn(page);

        mockMvc.perform(
                        get("/api/v1/attachments/search")
                                .param("awardNumber", "200086-00001")
                )
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].availabilityStatus").value("Pending upload"))
                .andExpect(jsonPath("$.content[0].downloadable").value(false));
    }

    @Test
    void searchNeverExposesAnS3BucketS3KeyOrFileDataIdField() throws Exception {
        AttachmentSearchResultResponse result = new AttachmentSearchResultResponse(
                "AWARD", 3047454L, "200086-00001", "Title", "PI NAME", 165,
                "879423", 9001L, 5001L, "Notice of Award.pdf",
                "Notice of Award", null, 1_800_000L, "application/pdf",
                "Available", true, true
        );
        PageResponse<AttachmentSearchResultResponse> page =
                new PageResponse<>(List.of(result), 0, 25, 1L, 1, true, true);
        when(service.searchAttachments(
                null, null, "200086-00001", null, null, null, null, null, "all", 0, 25
        )).thenReturn(page);

        mockMvc.perform(
                        get("/api/v1/attachments/search")
                                .param("awardNumber", "200086-00001")
                )
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].s3Bucket").doesNotExist())
                .andExpect(jsonPath("$.content[0].s3Key").doesNotExist())
                .andExpect(jsonPath("$.content[0].fileDataId").doesNotExist())
                .andExpect(jsonPath("$.content[0].storagePath").doesNotExist());
    }

    @Test
    void searchByExactProposalNumberReturnsAPageOfResults() throws Exception {
        AttachmentSearchResultResponse result = new AttachmentSearchResultResponse(
                "PROPOSAL", 7125L, "2975", "Title", "PI NAME", 4,
                "879423", 501508L, null, "Notice.pdf",
                "Notice", null, 1_800_000L, "application/pdf",
                "Available", true, true
        );
        PageResponse<AttachmentSearchResultResponse> page =
                new PageResponse<>(List.of(result), 0, 25, 1L, 1, true, true);
        when(service.searchAttachments(
                "PROPOSAL", "2975", null, null, null, null, null, null, "all", 0, 25
        )).thenReturn(page);

        mockMvc.perform(
                        get("/api/v1/attachments/search")
                                .param("recordType", "PROPOSAL")
                                .param("recordNumber", "2975")
                )
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].recordType").value("PROPOSAL"))
                .andExpect(jsonPath("$.content[0].parentNumber").value("2975"))
                .andExpect(jsonPath("$.content[0].fileId").doesNotExist());

        verify(service).searchAttachments(
                "PROPOSAL", "2975", null, null, null, null, null, null, "all", 0, 25
        );
    }

    @Test
    void searchWithRecordTypeAllForwardsToTheService() throws Exception {
        AttachmentSearchResultResponse awardResult = new AttachmentSearchResultResponse(
                "AWARD", 3047454L, "200086-00001", "Title", "PI", 165,
                "879423", 9001L, 5001L, "Notice of Award.pdf",
                "Notice of Award", null, 1_800_000L, "application/pdf",
                "Available", true, true
        );
        AttachmentSearchResultResponse proposalResult = new AttachmentSearchResultResponse(
                "PROPOSAL", 7125L, "2975", "Title", "PI", 4,
                "879423", 501508L, null, "Notice.pdf",
                "Notice", null, 1_800_000L, "application/pdf",
                "Available", true, true
        );
        PageResponse<AttachmentSearchResultResponse> page = new PageResponse<>(
                List.of(awardResult, proposalResult), 0, 25, 2L, 1, true, true
        );
        when(service.searchAttachments(
                "ALL", "879423", null, "879423", null, null, null, null, "all", 0, 25
        )).thenReturn(page);

        mockMvc.perform(
                        get("/api/v1/attachments/search")
                                .param("recordType", "ALL")
                                .param("recordNumber", "879423")
                                .param("documentNumber", "879423")
                )
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].recordType").value("AWARD"))
                .andExpect(jsonPath("$.content[1].recordType").value("PROPOSAL"));
    }
}
