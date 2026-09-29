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
    private edu.bu.archive.application.award.report.AwardConsolidatedReportAssembler
            consolidatedReportAssembler;

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

    // --- Consolidated report (report + archived attachments) ------------
    //
    // report-with-attachments.pdf embeds attachment FILE CONTENT, so it is
    // held to the same ArchiveAttachmentViewer policy as the attachment
    // list/download routes above. It previously skipped that check.

    private static final String REPORT_WITH_ATTACHMENTS =
            "/api/v1/awards/1833767/report-with-attachments.pdf";

    private void givenReportData() {
        edu.bu.archive.application.award.report.AwardReportData data =
                org.mockito.Mockito.mock(
                        edu.bu.archive.application.award.report.AwardReportData.class);
        edu.bu.archive.adapter.in.web.dto.award.AwardSummaryResponse summary =
                org.mockito.Mockito.mock(
                        edu.bu.archive.adapter.in.web.dto.award.AwardSummaryResponse.class);
        when(summary.awardNumber()).thenReturn("105698-00001");
        when(data.summary()).thenReturn(summary);
        when(reportService.buildReportData(1833767L)).thenReturn(data);
    }

    @Test
    void consolidatedReportWithoutAuthenticationIsRejected() throws Exception {
        mockMvc.perform(get(REPORT_WITH_ATTACHMENTS))
                .andExpect(status().isUnauthorized());

        org.mockito.Mockito.verifyNoInteractions(reportService, consolidatedReportAssembler);
    }

    @Test
    void consolidatedReportIsForbiddenWithoutTheAttachmentGroup() throws Exception {
        givenReportData();

        mockMvc.perform(get(REPORT_WITH_ATTACHMENTS).with(jwt()))
                .andExpect(status().isForbidden())
                .andExpect(content().string(
                        org.hamcrest.Matchers.containsString("ATTACHMENT_ACCESS_DENIED")));

        // Denied before anything is loaded or assembled: no report data,
        // no attachment metadata, no attachment bytes.
        org.mockito.Mockito.verify(reportService, org.mockito.Mockito.never())
                .buildReportData(org.mockito.ArgumentMatchers.anyLong());
        org.mockito.Mockito.verify(reportService, org.mockito.Mockito.never())
                .findReportAttachments(org.mockito.ArgumentMatchers.anyLong());
        org.mockito.Mockito.verifyNoInteractions(consolidatedReportAssembler);
    }

    @Test
    void consolidatedReportIsForbiddenForAnUnrelatedGroup() throws Exception {
        mockMvc.perform(get(REPORT_WITH_ATTACHMENTS)
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_SomeOtherGroup"))))
                .andExpect(status().isForbidden());

        org.mockito.Mockito.verifyNoInteractions(consolidatedReportAssembler);
    }

    @Test
    void attachmentViewerReceivesTheConsolidatedReport() throws Exception {
        givenReportData();
        List<edu.bu.archive.application.award.report.AwardReportAttachment> attachments =
                List.of();
        when(reportService.findReportAttachments(1833767L)).thenReturn(attachments);
        org.mockito.Mockito.doAnswer(invocation -> {
            java.io.OutputStream out = invocation.getArgument(2);
            out.write("%PDF-consolidated".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
            return null;
        }).when(consolidatedReportAssembler).assemble(
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.eq(attachments),
                org.mockito.ArgumentMatchers.any());

        var initial = mockMvc.perform(get(REPORT_WITH_ATTACHMENTS).with(attachmentViewer()))
                .andReturn();
        // See the streaming note in authenticatedAttachmentViewerCanDownloadAttachment.
        initial.getAsyncResult();

        org.assertj.core.api.Assertions.assertThat(initial.getResponse().getStatus())
                .isEqualTo(200);
        org.assertj.core.api.Assertions.assertThat(initial.getResponse().getContentType())
                .isEqualTo("application/pdf");
        org.assertj.core.api.Assertions.assertThat(initial.getResponse().getContentAsString())
                .isEqualTo("%PDF-consolidated");
        org.mockito.Mockito.verify(consolidatedReportAssembler).assemble(
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.eq(attachments),
                org.mockito.ArgumentMatchers.any());
    }

    @Test
    void theReportWithoutAttachmentsStaysAvailableWithoutTheAttachmentGroup() throws Exception {
        givenReportData();

        var initial = mockMvc.perform(get("/api/v1/awards/1833767/report.pdf").with(jwt()))
                .andReturn();
        initial.getAsyncResult();

        // Unchanged behaviour: report.pdf carries no attachment content, so
        // it remains open to every authenticated user.
        org.assertj.core.api.Assertions.assertThat(initial.getResponse().getStatus())
                .isEqualTo(200);
        org.mockito.Mockito.verify(reportPdfRenderer).render(
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(java.io.OutputStream.class));
        org.mockito.Mockito.verifyNoInteractions(consolidatedReportAssembler);
        org.mockito.Mockito.verify(reportService, org.mockito.Mockito.never())
                .findReportAttachments(org.mockito.ArgumentMatchers.anyLong());
    }
}
