package edu.bu.archive.adapter.in.web;

import edu.bu.archive.adapter.in.web.dto.negotiation.NegotiationAssociatedRecordResponse;
import edu.bu.archive.adapter.in.web.dto.negotiation.NegotiationAttachmentResponse;
import edu.bu.archive.adapter.in.web.dto.negotiation.NegotiationSummaryResponse;
import edu.bu.archive.adapter.in.web.dto.PageResponse;
import edu.bu.archive.application.negotiation.NegotiationArchiveService;
import edu.bu.archive.application.negotiation.NegotiationAttachmentDownload;
import edu.bu.archive.application.security.AttachmentAuthorizationService;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.io.ByteArrayInputStream;
import java.util.List;
import java.util.NoSuchElementException;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class NegotiationArchiveControllerTest {

    private NegotiationArchiveService service;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        service = mock(NegotiationArchiveService.class);
        NegotiationArchiveController controller =
                new NegotiationArchiveController(
                        service, mock(AttachmentAuthorizationService.class)
                );
        mockMvc = MockMvcBuilders
                .standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    void searchUsesTheRootRouteAndDelegatesPagination()
            throws Exception {
        PageResponse<NegotiationSummaryResponse> page = new PageResponse<>(
                List.of(),
                2,
                10,
                0,
                0,
                false,
                true
        );

        when(service.findPage("award", 2, 10))
                .thenReturn(page);

        mockMvc.perform(
                        get("/api/negotiations")
                                .param("query", "award")
                                .param("page", "2")
                                .param("size", "10")
                )
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page").value(2))
                .andExpect(jsonPath("$.size").value(10));

        verify(service).findPage("award", 2, 10);
    }

    @Test
    void notificationsReturnsAnEmptyJsonCollection()
            throws Exception {
        when(service.findNotifications(101L))
                .thenReturn(List.of());

        mockMvc.perform(
                        get("/api/negotiations/101/notifications")
                )
                .andExpect(status().isOk())
                .andExpect(content().json("[]"));

        verify(service).findNotifications(101L);
    }

    @Test
    void activitiesUsesTheNegotiationIdRoute()
            throws Exception {
        when(service.findActivities(101L))
                .thenReturn(List.of());

        mockMvc.perform(
                        get("/api/negotiations/101/activities")
                )
                .andExpect(status().isOk());

        verify(service).findActivities(101L);
    }

    @Test
    void attachmentsUsesTheNegotiationIdRoute() throws Exception {
        NegotiationAttachmentResponse attachment =
                new NegotiationAttachmentResponse(
                        1L, 9952L, "notice.pdf", "application/pdf",
                        100L, "ARCHIVED", null, null, true, "N",
                        101L, "24828", "Kotton Proteostasis"
                );
        when(service.findAttachments(101L))
                .thenReturn(List.of(attachment));

        mockMvc.perform(get("/api/negotiations/101/attachments"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].attachmentId").value(1))
                .andExpect(jsonPath("$[0].activityId").value(9952));

        verify(service).findAttachments(101L);
    }

    @Test
    void downloadAttachmentStreamsTheFileWithAContentDispositionHeader()
            throws Exception {
        when(service.downloadAttachment(101L, 1L))
                .thenReturn(new NegotiationAttachmentDownload(
                        "notice.pdf",
                        "application/pdf",
                        3L,
                        new ByteArrayInputStream(new byte[]{1, 2, 3})
                ));

        mockMvc.perform(
                        get("/api/negotiations/101/attachments/1/download")
                )
                .andExpect(status().isOk())
                .andExpect(header().string(
                        "Content-Disposition",
                        org.hamcrest.Matchers.containsString("notice.pdf")
                ));
    }

    @Test
    void downloadAttachmentPropagatesANotFoundAttachment() throws Exception {
        when(service.downloadAttachment(101L, 999L))
                .thenThrow(new NoSuchElementException(
                        "Negotiation attachment not found"
                ));

        mockMvc.perform(
                        get("/api/negotiations/101/attachments/999/download")
                )
                .andExpect(status().isNotFound());
    }

    @Test
    void associatedRecordUsesTheNegotiationIdRoute() throws Exception {
        when(service.findAssociatedRecord(101L))
                .thenReturn(new NegotiationAssociatedRecordResponse(
                        "AWD", "Award", "204107-00001",
                        "AWARD", 555L, true
                ));

        mockMvc.perform(get("/api/negotiations/101/associated-record"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.kind").value("AWARD"))
                .andExpect(jsonPath("$.navigableId").value(555))
                .andExpect(jsonPath("$.clickable").value(true));

        verify(service).findAssociatedRecord(101L);
    }
}
