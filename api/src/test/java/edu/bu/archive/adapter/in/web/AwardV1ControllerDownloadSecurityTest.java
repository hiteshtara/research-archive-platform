package edu.bu.archive.adapter.in.web;

import edu.bu.archive.application.award.AwardArchiveService;
import edu.bu.archive.application.award.AwardAttachmentDownload;
import edu.bu.archive.application.award.AwardContactService;
import edu.bu.archive.application.security.AttachmentAuthorizationService;
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

import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/*
 * Regression coverage for the Award attachment routes' authorization
 * behavior. Originally documented that this app had no
 * "authenticated but insufficiently privileged" tier at all - that is
 * no longer true: AttachmentAuthorizationService now requires the
 * ArchiveAttachmentViewer group (mapped from the Cognito cognito:groups
 * claim to ROLE_ArchiveAttachmentViewer by
 * SecurityConfiguration.jwtAuthenticationConverter()) for every
 * attachment endpoint specifically - every other /api/** route is
 * unaffected and keeps the original plain-authenticated rule. See
 * docs/architecture/NEGOTIATION_ATTACHMENT_ACCESS_DESIGN.md.
 */
@WebMvcTest(AwardV1Controller.class)
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
class AwardV1ControllerDownloadSecurityTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private AwardArchiveService service;

    @MockitoBean
    private AwardContactService contactService;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    private static org.springframework.test.web.servlet.request.RequestPostProcessor
            attachmentViewer() {
        return jwt().authorities(new SimpleGrantedAuthority(
                AttachmentAuthorizationService.ATTACHMENT_VIEWER_AUTHORITY
        ));
    }

    @Test
    void downloadWithoutAuthenticationIsRejected() throws Exception {
        mockMvc.perform(
                        get("/api/v1/awards/1833767/attachments/306557/download")
                )
                .andExpect(status().isUnauthorized());
    }

    @Test
    void authenticatedUserWithoutAttachmentGroupIsRejected() throws Exception {
        mockMvc.perform(
                        get("/api/v1/awards/1833767/attachments/306557/download")
                                .with(jwt())
                )
                .andExpect(status().isForbidden());

        org.mockito.Mockito.verifyNoInteractions(service);
    }

    @Test
    void authenticatedAttachmentViewerCanDownloadAttachment() throws Exception {
        when(service.downloadAttachment(1833767L, 306557L))
                .thenReturn(new AwardAttachmentDownload(
                        "agreement.pdf",
                        "application/pdf",
                        4,
                        new ByteArrayInputStream(new byte[]{1, 2, 3, 4})
                ));

        var initial = mockMvc.perform(
                        get("/api/v1/awards/1833767/attachments/306557/download")
                                .with(attachmentViewer())
                )
                .andExpect(status().isOk())
                .andReturn();

        // downloadAttachment streams its response body via
        // StreamingResponseBody (async dispatch) - the two-step
        // perform()/asyncDispatch() sequence is required to let MockMvc
        // wait for and re-inspect the async result, mirroring
        // SubawardArchiveControllerTest's existing download tests.
        // Skipping this step (a single perform().andExpect(isOk())) is
        // unstable under the real Spring Security filter chain - it
        // intermittently throws ConcurrentModificationException from
        // HeaderWriterFilter racing the async dispatch thread.
        mockMvc.perform(asyncDispatch(initial))
                .andExpect(status().isOk());
    }

    @Test
    void listWithoutAuthenticationIsRejected() throws Exception {
        mockMvc.perform(get("/api/v1/awards/1833767/attachments"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void listWithoutAttachmentGroupIsRejectedAndLeaksNoMetadata()
            throws Exception {
        mockMvc.perform(
                        get("/api/v1/awards/1833767/attachments")
                                .with(jwt())
                )
                .andExpect(status().isForbidden())
                .andExpect(content().string(
                        org.hamcrest.Matchers.not(
                                org.hamcrest.Matchers.containsString(
                                        "agreement.pdf"
                                )
                        )
                ));

        org.mockito.Mockito.verifyNoInteractions(service);
    }

    @Test
    void listWithAttachmentGroupSucceeds() throws Exception {
        when(service.findAttachments(1833767L, 0, 25))
                .thenReturn(new edu.bu.archive.adapter.in.web.dto.PageResponse<>(
                        List.of(), 0, 25, 0L, 0, true, true
                ));

        mockMvc.perform(
                        get("/api/v1/awards/1833767/attachments")
                                .with(attachmentViewer())
                )
                .andExpect(status().isOk());
    }
}
