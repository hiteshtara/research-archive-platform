package edu.bu.archive.adapter.in.web;

import edu.bu.archive.application.security.AttachmentAuthorizationService;
import edu.bu.archive.application.subaward.SubawardArchiveService;
import edu.bu.archive.application.subaward.SubawardAttachmentDownload;
import edu.bu.archive.config.SecurityConfiguration;

import java.io.ByteArrayInputStream;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/*
 * Subaward attachment routes now require the ArchiveAttachmentViewer
 * group, same gate as Award's/Proposal's - see
 * AwardV1ControllerDownloadSecurityTest and AttachmentAuthorizationService.
 */
@WebMvcTest(SubawardArchiveController.class)
@Import({
        SecurityConfiguration.class,
        GlobalExceptionHandler.class,
        AttachmentAuthorizationService.class
})
@TestPropertySource(properties = {
        "app.security.enabled=true",
        "app.security.cognito.issuer-uri=https://issuer.example",
        "app.security.cognito.client-id=test-client"
})
class SubawardArchiveControllerAttachmentSecurityTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private SubawardArchiveService service;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    private static RequestPostProcessor attachmentViewer() {
        return jwt().authorities(new SimpleGrantedAuthority(
                AttachmentAuthorizationService.ATTACHMENT_VIEWER_AUTHORITY
        ));
    }

    @Test
    void listWithoutAuthenticationIsRejected() throws Exception {
        mockMvc.perform(get("/api/subawards/55/attachments"))
                .andExpect(status().isUnauthorized());
    }

    /*
     * The new /{subawardId}/versions endpoint (Archived Versions):
     * requires plain authentication like every other non-attachment
     * Subaward route (workspace/amounts/etc.) - proven here alongside
     * the attachment-route tests above/below specifically to show the
     * attachment-only boundary is preserved, not accidentally widened
     * or narrowed by this addition.
     */
    @Test
    void versionsWithoutAuthenticationIsRejected() throws Exception {
        mockMvc.perform(get("/api/subawards/55/versions"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void versionsWithPlainAuthenticationSucceedsWithoutTheAttachmentGroup()
            throws Exception {
        when(service.findVersions(55L, 0, 25)).thenReturn(
                new edu.bu.archive.adapter.in.web.dto.PageResponse<>(
                        List.of(), 0, 25, 0L, 0, true, true
                )
        );

        mockMvc.perform(get("/api/subawards/55/versions").with(jwt()))
                .andExpect(status().isOk());
    }

    @Test
    void versionsRejectsAnOutOfRangeSizeAsABadRequestNotAServerError()
            throws Exception {
        mockMvc.perform(
                        get("/api/subawards/55/versions")
                                .param("size", "0")
                                .with(jwt())
                )
                .andExpect(status().isBadRequest());

        mockMvc.perform(
                        get("/api/subawards/55/versions")
                                .param("size", "101")
                                .with(jwt())
                )
                .andExpect(status().isBadRequest());

        verifyNoInteractions(service);
    }

    @Test
    void listWithoutAttachmentGroupIsRejected() throws Exception {
        mockMvc.perform(
                        get("/api/subawards/55/attachments").with(jwt())
                )
                .andExpect(status().isForbidden());

        verifyNoInteractions(service);
    }

    @Test
    void listWithAttachmentGroupSucceeds() throws Exception {
        when(service.findAttachments(55L)).thenReturn(List.of());

        mockMvc.perform(
                        get("/api/subawards/55/attachments")
                                .with(attachmentViewer())
                )
                .andExpect(status().isOk());
    }

    @Test
    void downloadWithoutAuthenticationIsRejected() throws Exception {
        mockMvc.perform(
                        get("/api/subawards/55/attachments/12/download")
                )
                .andExpect(status().isUnauthorized());
    }

    @Test
    void downloadWithoutAttachmentGroupIsRejected() throws Exception {
        mockMvc.perform(
                        get("/api/subawards/55/attachments/12/download")
                                .with(jwt())
                )
                .andExpect(status().isForbidden());

        verifyNoInteractions(service);
    }

    @Test
    void downloadWithAttachmentGroupSucceeds() throws Exception {
        when(service.downloadAttachment(55L, 12L))
                .thenReturn(new SubawardAttachmentDownload(
                        "subaward.pdf",
                        "application/pdf",
                        4,
                        new ByteArrayInputStream(new byte[]{1, 2, 3, 4})
                ));

        var initial = mockMvc.perform(
                        get("/api/subawards/55/attachments/12/download")
                                .with(attachmentViewer())
                )
                .andExpect(status().isOk())
                .andReturn();

        mockMvc.perform(asyncDispatch(initial))
                .andExpect(status().isOk());
    }
}
