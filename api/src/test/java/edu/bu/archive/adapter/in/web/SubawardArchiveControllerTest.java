package edu.bu.archive.adapter.in.web;

import edu.bu.archive.adapter.in.web.dto.subaward.SubawardPageResponse;
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
                new SubawardArchiveController(service);
        mockMvc = MockMvcBuilders
                .standaloneSetup(controller)
                .setControllerAdvice(new AwardExceptionHandler())
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
