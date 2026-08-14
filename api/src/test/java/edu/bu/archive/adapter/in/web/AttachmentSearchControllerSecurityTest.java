package edu.bu.archive.adapter.in.web;

import edu.bu.archive.adapter.in.web.dto.PageResponse;
import edu.bu.archive.adapter.in.web.dto.attachment.AttachmentSearchResultResponse;
import edu.bu.archive.application.security.AttachmentAuthorizationService;
import edu.bu.archive.application.service.AttachmentSearchService;
import edu.bu.archive.config.SecurityConfiguration;

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

import java.util.List;

import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;

/*
 * Archived File Finder now requires two things, not one: Cognito
 * authentication (401 without a token, the original blanket
 * .requestMatchers("/api/**").authenticated() rule every /api/** route
 * uses) AND membership in the ArchiveAttachmentViewer group (403
 * otherwise) - see AttachmentAuthorizationService and
 * docs/architecture/NEGOTIATION_ATTACHMENT_ACCESS_DESIGN.md. No
 * attachment metadata is ever computed for a request that fails either
 * gate - requireAttachmentAccess is the very first line of the
 * controller method, before the service is called at all.
 */
@WebMvcTest(AttachmentSearchController.class)
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
class AttachmentSearchControllerSecurityTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private AttachmentSearchService service;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    private static RequestPostProcessor attachmentViewer() {
        return jwt().authorities(new SimpleGrantedAuthority(
                AttachmentAuthorizationService.ATTACHMENT_VIEWER_AUTHORITY
        ));
    }

    @Test
    void searchWithoutAuthenticationIsRejected() throws Exception {
        mockMvc.perform(
                        get("/api/v1/attachments/search")
                                .param("awardNumber", "200086-00001")
                )
                .andExpect(status().isUnauthorized());
    }

    @Test
    void authenticatedUserWithoutAttachmentGroupIsRejected() throws Exception {
        mockMvc.perform(
                        get("/api/v1/attachments/search")
                                .param("awardNumber", "200086-00001")
                                .with(jwt())
                )
                .andExpect(status().isForbidden());

        verifyNoInteractions(service);
    }

    @Test
    void searchWithoutAttachmentGroupLeaksNoResultMetadata() throws Exception {
        AttachmentSearchResultResponse result = new AttachmentSearchResultResponse(
                "AWARD", 3047454L, "200086-00001", "Title", "PI NAME", 165,
                "879423", 9001L, 5001L, "Notice of Award.pdf",
                "Notice of Award", null, 1_800_000L, "application/pdf",
                "Available", true, true
        );
        // Even if the service were somehow invoked and returned real
        // results, requireAttachmentAccess runs first and the mocked
        // service below is never asked for anything - verifyNoInteractions
        // is the real proof; the content assertion is a second, belt and
        // braces check that the specific filename never appears.
        when(service.searchAttachments(
                null, null, "200086-00001", null, null, null, null, null, "all", 0, 25
        )).thenReturn(new PageResponse<>(List.of(result), 0, 25, 1L, 1, true, true));

        mockMvc.perform(
                        get("/api/v1/attachments/search")
                                .param("awardNumber", "200086-00001")
                                .with(jwt())
                )
                .andExpect(status().isForbidden())
                .andExpect(content().string(
                        not(containsString("Notice of Award.pdf"))
                ));

        verifyNoInteractions(service);
    }

    @Test
    void authenticatedUserCanSearch() throws Exception {
        AttachmentSearchResultResponse result = new AttachmentSearchResultResponse(
                "AWARD", 3047454L, "200086-00001", "Title", "PI NAME", 165,
                "879423", 9001L, 5001L, "Notice of Award.pdf",
                "Notice of Award", null, 1_800_000L, "application/pdf",
                "Available", true, true
        );
        when(service.searchAttachments(
                null, null, "200086-00001", null, null, null, null, null, "all", 0, 25
        )).thenReturn(new PageResponse<>(List.of(result), 0, 25, 1L, 1, true, true));

        mockMvc.perform(
                        get("/api/v1/attachments/search")
                                .param("awardNumber", "200086-00001")
                                .with(attachmentViewer())
                )
                .andExpect(status().isOk());
    }

    @Test
    void proposalSearchAlsoRequiresAuthentication() throws Exception {
        mockMvc.perform(
                        get("/api/v1/attachments/search")
                                .param("recordType", "PROPOSAL")
                                .param("recordNumber", "2975")
                )
                .andExpect(status().isUnauthorized());
    }

    @Test
    void proposalSearchAlsoRequiresTheAttachmentGroup() throws Exception {
        mockMvc.perform(
                        get("/api/v1/attachments/search")
                                .param("recordType", "PROPOSAL")
                                .param("recordNumber", "2975")
                                .with(jwt())
                )
                .andExpect(status().isForbidden());

        verifyNoInteractions(service);
    }

    @Test
    void authenticatedUserCanSearchProposal() throws Exception {
        AttachmentSearchResultResponse result = new AttachmentSearchResultResponse(
                "PROPOSAL", 7125L, "2975", "Title", "PI NAME", 4,
                "879423", 501508L, null, "Notice.pdf",
                "Notice", null, 1_800_000L, "application/pdf",
                "Available", true, true
        );
        when(service.searchAttachments(
                "PROPOSAL", "2975", null, null, null, null, null, null, "all", 0, 25
        )).thenReturn(new PageResponse<>(List.of(result), 0, 25, 1L, 1, true, true));

        mockMvc.perform(
                        get("/api/v1/attachments/search")
                                .param("recordType", "PROPOSAL")
                                .param("recordNumber", "2975")
                                .with(attachmentViewer())
                )
                .andExpect(status().isOk());
    }
}
