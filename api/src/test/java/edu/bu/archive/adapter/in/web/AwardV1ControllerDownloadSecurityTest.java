package edu.bu.archive.adapter.in.web;

import edu.bu.archive.application.award.AwardArchiveService;
import edu.bu.archive.application.award.AwardAttachmentDownload;
import edu.bu.archive.application.award.AwardContactService;
import edu.bu.archive.config.SecurityConfiguration;

import java.io.ByteArrayInputStream;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/*
 * Regression coverage for the Award attachment download route's
 * authorization behavior specifically - added after a live report of
 * HTTP 403 "insufficient_scope" on GET
 * /api/v1/awards/{awardId}/attachments/{attachmentId}/download.
 *
 * That symptom was investigated and NOT reproduced: SecurityConfiguration
 * applies exactly one rule to every /api/** route, including this one -
 * .requestMatchers("/api/**").authenticated() - with no
 * .hasAuthority(...)/.hasRole(...)/@PreAuthorize/scope requirement
 * anywhere in this codebase (verified by full-tree grep) or in the
 * Cognito app client's allowed_oauth_scopes (openid/email/profile only -
 * no custom resource server/scope is even defined). A real CloudWatch
 * log line for the exact reported request timestamp confirms the
 * request passed both authentication and Spring Security's
 * AuthorizationFilter and reached AwardArchiveService.downloadAttachment,
 * which then failed for an unrelated reason (a missing
 * ARCHIVE_DOCUMENTS_BUCKET environment variable in S3AwardAttachmentStorage
 * - a deployment configuration gap, out of scope here per "do not change
 * S3").
 *
 * This test proves the download route's actual, current authorization
 * behavior: authentication is required (401 without a token) and no
 * additional scope/role is required beyond it (200 for any authenticated
 * user, identical to every other /api/** route) - i.e. it already uses
 * the same archive-read permission as the rest of the Award API, not a
 * stricter one. There is no "genuinely unauthorized but authenticated"
 * tier in this app's authorization model to test a 403 against - every
 * authenticated caller has the same single permission level for every
 * /api/** route (see SecurityConfiguration) - so no such case exists to
 * assert without inventing a rule that isn't there.
 */
@WebMvcTest(AwardV1Controller.class)
@Import({
        SecurityConfiguration.class,
        GlobalExceptionHandler.class
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

    @Test
    void downloadWithoutAuthenticationIsRejected() throws Exception {
        mockMvc.perform(
                        get("/api/v1/awards/1833767/attachments/306557/download")
                )
                .andExpect(status().isUnauthorized());
    }

    @Test
    void authenticatedUserCanDownloadAttachment() throws Exception {
        when(service.downloadAttachment(1833767L, 306557L))
                .thenReturn(new AwardAttachmentDownload(
                        "agreement.pdf",
                        "application/pdf",
                        4,
                        new ByteArrayInputStream(new byte[]{1, 2, 3, 4})
                ));

        var initial = mockMvc.perform(
                        get("/api/v1/awards/1833767/attachments/306557/download")
                                .with(jwt())
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
}
