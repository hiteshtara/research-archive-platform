package edu.bu.archive.adapter.in.web;

import edu.bu.archive.adapter.in.web.dto.PageResponse;
import edu.bu.archive.adapter.in.web.dto.subaward.SubawardPageResponse;
import edu.bu.archive.adapter.in.web.dto.subaward.SubawardVersionSummaryResponse;
import edu.bu.archive.application.security.AttachmentAuthorizationService;
import edu.bu.archive.application.subaward.SubawardArchiveService;
import edu.bu.archive.application.subaward.SubawardAttachmentDownload;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.io.ByteArrayInputStream;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class SubawardArchiveControllerTest {

    private SubawardArchiveService service;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        service = mock(SubawardArchiveService.class);
        SubawardArchiveController controller =
                new SubawardArchiveController(
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
        SubawardPageResponse page = new SubawardPageResponse(
                List.of(), 2, 10, 0, 0, false, true
        );
        when(service.findPage("1004", 2, 10)).thenReturn(page);

        mockMvc.perform(
                        get("/api/subawards")
                                .param("query", "1004")
                                .param("page", "2")
                                .param("size", "10")
                )
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page").value(2))
                .andExpect(jsonPath("$.size").value(10));

        verify(service).findPage("1004", 2, 10);
    }

    @Test
    void versionsIsRoutedUnderTheSubawardIdAndDelegatesPagination()
            throws Exception {
        SubawardVersionSummaryResponse version =
                new SubawardVersionSummaryResponse(
                        90085L, "1004", 25, "DOC-25", "Active",
                        null, null, null, true
                );
        PageResponse<SubawardVersionSummaryResponse> page =
                new PageResponse<>(List.of(version), 0, 25, 25L, 1, true, true);
        when(service.findVersions(90085L, 0, 25)).thenReturn(page);

        mockMvc.perform(get("/api/subawards/90085/versions"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(25))
                .andExpect(jsonPath("$.content[0].subawardId").value(90085))
                .andExpect(jsonPath("$.content[0].subawardCode").value("1004"))
                .andExpect(jsonPath("$.content[0].sequenceNumber").value(25))
                .andExpect(jsonPath("$.content[0].latestVersion").value(true));

        verify(service).findVersions(90085L, 0, 25);
    }

    @Test
    void versionsPassesPageAndSizeThrough() throws Exception {
        when(service.findVersions(90085L, 1, 10))
                .thenReturn(new PageResponse<>(List.of(), 1, 10, 0L, 0, true, true));

        mockMvc.perform(
                        get("/api/subawards/90085/versions")
                                .param("page", "1")
                                .param("size", "10")
                )
                .andExpect(status().isOk());

        verify(service).findVersions(90085L, 1, 10);
    }

    @Test
    void versionsPropagatesNotFoundAsAnHttp404() throws Exception {
        when(service.findVersions(999L, 0, 25))
                .thenThrow(new java.util.NoSuchElementException(
                        "Subaward not found: 999"
                ));

        mockMvc.perform(get("/api/subawards/999/versions"))
                .andExpect(status().isNotFound());
    }

    @Test
    void versionsReturnsAnEmptyPageForACodeWithNoOtherVersions()
            throws Exception {
        when(service.findVersions(101L, 0, 25))
                .thenReturn(new PageResponse<>(List.of(), 0, 25, 0L, 0, true, true));

        mockMvc.perform(get("/api/subawards/101/versions"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(0))
                .andExpect(jsonPath("$.content").isEmpty());
    }

    @Test
    void versionsToleratesANullDocumentNumber() throws Exception {
        SubawardVersionSummaryResponse versionWithoutDocumentNumber =
                new SubawardVersionSummaryResponse(
                        90080L, "1004", 20, null, "Active",
                        null, null, null, false
                );
        when(service.findVersions(90085L, 0, 25)).thenReturn(
                new PageResponse<>(
                        List.of(versionWithoutDocumentNumber), 0, 25, 1L, 1, true, true
                )
        );

        mockMvc.perform(get("/api/subawards/90085/versions"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].documentNumber").doesNotExist())
                .andExpect(jsonPath("$.content[0].subawardId").value(90080));
    }

    @Test
    void notificationsReturnsAnEmptyJsonCollection()
            throws Exception {
        when(service.findNotifications(101L)).thenReturn(List.of());

        mockMvc.perform(get("/api/subawards/101/notifications"))
                .andExpect(status().isOk())
                .andExpect(content().json("[]"));

        verify(service).findNotifications(101L);
    }

    @Test
    void closeoutUsesTheSubawardIdRouteAndReturnsAnEmptyCollection()
            throws Exception {
        when(service.findCloseout(101L)).thenReturn(List.of());

        mockMvc.perform(get("/api/subawards/101/closeout"))
                .andExpect(status().isOk())
                .andExpect(content().json("[]"));

        verify(service).findCloseout(101L);
    }

    @Test
    void downloadsPdfWithTheArchivedContentHeaders() throws Exception {
        assertDownload(
                500L,
                "proposal.pdf",
                "application/pdf",
                new byte[]{1, 2, 3}
        );
    }

    @Test
    void downloadsDocxWithTheArchivedContentHeaders() throws Exception {
        assertDownload(
                501L,
                "agreement.docx",
                "application/vnd.openxmlformats-officedocument"
                        + ".wordprocessingml.document",
                new byte[]{4, 5, 6, 7}
        );
    }

    private void assertDownload(
            long attachmentId,
            String fileName,
            String mimeType,
            byte[] content
    ) throws Exception {
        when(service.downloadAttachment(94202L, attachmentId))
                .thenReturn(new SubawardAttachmentDownload(
                        fileName,
                        mimeType,
                        content.length,
                        new ByteArrayInputStream(content)
                ));

        var initial = mockMvc.perform(get(
                        "/api/subawards/94202/attachments/"
                                + attachmentId + "/download"
                ))
                .andExpect(status().isOk())
                .andReturn();

        mockMvc.perform(asyncDispatch(initial))
                .andExpect(status().isOk())
                .andExpect(content().bytes(content))
                .andExpect(
                        org.springframework.test.web.servlet.result
                                .MockMvcResultMatchers.header()
                                .string(
                                        "Content-Type",
                                        mimeType
                                )
                )
                .andExpect(
                        org.springframework.test.web.servlet.result
                                .MockMvcResultMatchers.header()
                                .string(
                                        "Content-Disposition",
                                        org.hamcrest.Matchers
                                                .containsString(fileName)
                                )
                );

        verify(service).downloadAttachment(94202L, attachmentId);
    }
}
