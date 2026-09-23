package edu.bu.archive.adapter.in.web;

import edu.bu.archive.application.award.AwardArchiveService;
import edu.bu.archive.application.award.AwardAttachmentDownload;
import edu.bu.archive.application.award.AwardContactService;
import edu.bu.archive.application.award.report.AwardReportPdfRenderer;
import edu.bu.archive.application.award.report.AwardReportService;
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
    private AwardReportService reportService;

    @MockitoBean
    private AwardReportPdfRenderer reportPdfRenderer;

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
                .andReturn();

        // downloadAttachment streams its body via StreamingResponseBody.
        // Do NOT add a second mockMvc.perform(asyncDispatch(initial)) here.
        // For StreamingResponseBody that dispatch is a no-op for the
        // RESULT - it re-invokes neither the handler nor the service and
        // writes no further bytes - but it does drive the real Spring
        // Security filter chain over this same MockHttpServletResponse a
        // second time, while the first request's streaming worker may
        // still be writing and committing it. Two passes writing headers
        // into MockHttpServletResponse's LinkedCaseInsensitiveMap is what
        // intermittently threw ConcurrentModificationException from
        // HeaderWriterFilter under full-suite load.
        //
        // getAsyncResult() blocks until the streaming callable has
        // finished, so the response is complete and quiescent before it
        // is asserted on.
        initial.getAsyncResult();
        org.assertj.core.api.Assertions
                .assertThat(initial.getResponse().getStatus())
                .isEqualTo(200);
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
    void reportWithoutAuthenticationIsRejected() throws Exception {
        mockMvc.perform(get("/api/v1/awards/1833767/report.pdf"))
                .andExpect(status().isUnauthorized());

        org.mockito.Mockito.verifyNoInteractions(reportService);
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
