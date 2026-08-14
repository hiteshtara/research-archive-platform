package edu.bu.archive.adapter.in.web;

import edu.bu.archive.adapter.in.web.dto.PageResponse;
import edu.bu.archive.adapter.in.web.dto.attachment.AttachmentSearchResultResponse;
import edu.bu.archive.application.award.AwardArchiveService;
import edu.bu.archive.config.SecurityConfiguration;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/*
 * Archived File Finder is authenticated-only via the same blanket
 * .requestMatchers("/api/**").authenticated() rule every other /api/**
 * route uses (SecurityConfiguration) - Phase 1 introduces no bespoke
 * rule, narrower scope, or role tier of its own. Mirrors
 * ExplorerControllerSecurityTest/AwardV1ControllerDownloadSecurityTest
 * exactly: 401 without a token, 200 with one.
 */
@WebMvcTest(AttachmentSearchController.class)
@Import({
        SecurityConfiguration.class,
        GlobalExceptionHandler.class
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
    private AwardArchiveService service;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    @Test
    void searchWithoutAuthenticationIsRejected() throws Exception {
        mockMvc.perform(
                        get("/api/v1/attachments/search")
                                .param("awardNumber", "200086-00001")
                )
                .andExpect(status().isUnauthorized());
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
                "200086-00001", null, null, null, null, "all", 0, 25
        )).thenReturn(new PageResponse<>(List.of(result), 0, 25, 1L, 1, true, true));

        mockMvc.perform(
                        get("/api/v1/attachments/search")
                                .param("awardNumber", "200086-00001")
                                .with(jwt())
                )
                .andExpect(status().isOk());
    }
}
