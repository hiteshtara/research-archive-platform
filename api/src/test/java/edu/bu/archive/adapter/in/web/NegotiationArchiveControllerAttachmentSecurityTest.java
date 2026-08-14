package edu.bu.archive.adapter.in.web;

import edu.bu.archive.adapter.in.web.dto.negotiation.NegotiationAttachmentResponse;
import edu.bu.archive.application.negotiation.NegotiationArchiveService;
import edu.bu.archive.application.negotiation.NegotiationAttachmentDownload;
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
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/*
 * Negotiation attachment routes require the ArchiveAttachmentViewer
 * group, same gate as every other domain's - see
 * AwardV1ControllerDownloadSecurityTest and AttachmentAuthorizationService.
 * The legacy RESTRICTED flag (restrictedFlag on the response) is never
 * itself a factor in this decision - an authorized viewer sees both 'Y'
 * and 'N' attachments identically, proven below by returning one of
 * each from the mocked service and asserting both come back in the same
 * 200 response.
 */
@WebMvcTest(NegotiationArchiveController.class)
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
class NegotiationArchiveControllerAttachmentSecurityTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private NegotiationArchiveService service;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    private static RequestPostProcessor attachmentViewer() {
        return jwt().authorities(new SimpleGrantedAuthority(
                AttachmentAuthorizationService.ATTACHMENT_VIEWER_AUTHORITY
        ));
    }

    @Test
    void listWithoutAuthenticationIsRejected() throws Exception {
        mockMvc.perform(get("/api/negotiations/374/attachments"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void listWithoutAttachmentGroupIsRejected() throws Exception {
        mockMvc.perform(
                        get("/api/negotiations/374/attachments").with(jwt())
                )
                .andExpect(status().isForbidden());

        verifyNoInteractions(service);
    }

    @Test
    void restrictedYAndNAttachmentsAreBothReturnedToAnAuthorizedViewer()
            throws Exception {
        NegotiationAttachmentResponse restricted = new NegotiationAttachmentResponse(
                1L, 9921L, "restricted.pdf", "application/pdf",
                100L, "MISSING", null, null, false, "Y",
                201L, "39001", "Restricted example"
        );
        NegotiationAttachmentResponse notRestricted = new NegotiationAttachmentResponse(
                2L, 9942L, "open.pdf", "application/pdf",
                100L, "ARCHIVED", null, null, true, "N",
                202L, "39002", "Open example"
        );
        when(service.findAttachments(374L))
                .thenReturn(List.of(restricted, notRestricted));

        mockMvc.perform(
                        get("/api/negotiations/374/attachments")
                                .with(attachmentViewer())
                )
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].attachmentId").value(1))
                .andExpect(jsonPath("$[0].restrictedFlag").value("Y"))
                .andExpect(jsonPath("$[0].downloadable").value(false))
                .andExpect(jsonPath("$[1].attachmentId").value(2))
                .andExpect(jsonPath("$[1].restrictedFlag").value("N"))
                .andExpect(jsonPath("$[1].downloadable").value(true));
    }

    @Test
    void downloadWithoutAuthenticationIsRejected() throws Exception {
        mockMvc.perform(
                        get("/api/negotiations/374/attachments/2/download")
                )
                .andExpect(status().isUnauthorized());
    }

    @Test
    void downloadWithoutAttachmentGroupIsRejected() throws Exception {
        mockMvc.perform(
                        get("/api/negotiations/374/attachments/2/download")
                                .with(jwt())
                )
                .andExpect(status().isForbidden());

        verifyNoInteractions(service);
    }

    @Test
    void downloadOfANotRestrictedAttachmentSucceedsForAnAuthorizedViewer()
            throws Exception {
        when(service.downloadAttachment(374L, 2L))
                .thenReturn(new NegotiationAttachmentDownload(
                        "open.pdf",
                        "application/pdf",
                        4,
                        new ByteArrayInputStream(new byte[]{1, 2, 3, 4})
                ));

        var initial = mockMvc.perform(
                        get("/api/negotiations/374/attachments/2/download")
                                .with(attachmentViewer())
                )
                .andExpect(status().isOk())
                .andReturn();

        mockMvc.perform(asyncDispatch(initial))
                .andExpect(status().isOk());
    }

    @Test
    void downloadOfARestrictedAttachmentThatIsArchivedAlsoSucceedsForAnAuthorizedViewer()
            throws Exception {
        // RESTRICTED='Y' never gates the download endpoint itself either -
        // this proves it directly at the download route, not just the
        // list route above. downloadAttachment's own real gate (archive
        // status/S3 presence) is exercised elsewhere
        // (NegotiationArchiveServiceTest); here the point is only that
        // the legacy flag plays no role.
        when(service.downloadAttachment(374L, 1L))
                .thenReturn(new NegotiationAttachmentDownload(
                        "restricted.pdf",
                        "application/pdf",
                        4,
                        new ByteArrayInputStream(new byte[]{1, 2, 3, 4})
                ));

        var initial = mockMvc.perform(
                        get("/api/negotiations/374/attachments/1/download")
                                .with(attachmentViewer())
                )
                .andExpect(status().isOk())
                .andReturn();

        mockMvc.perform(asyncDispatch(initial))
                .andExpect(status().isOk());
    }
}
