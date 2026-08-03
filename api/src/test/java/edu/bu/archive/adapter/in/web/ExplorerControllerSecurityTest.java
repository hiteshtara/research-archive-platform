package edu.bu.archive.adapter.in.web;

import edu.bu.archive.adapter.in.web.dto.explorer.ExplorerAwardResponse;
import edu.bu.archive.application.award.ExplorerService;
import edu.bu.archive.config.SecurityConfiguration;

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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/*
 * The Archive Explorer is authenticated-only - it relies on the same
 * blanket .requestMatchers("/api/**").authenticated() rule every other
 * /api/** route already uses (SecurityConfiguration), not a bespoke rule
 * of its own. This proves that rule actually covers /api/v1/explorer/**:
 * 401 without a token, 200 with one, same as every other route (there is
 * no additional scope/role tier anywhere in this app - see
 * AwardV1ControllerDownloadSecurityTest for the equivalent proof on the
 * Award download route).
 */
@WebMvcTest(ExplorerController.class)
@Import({
        SecurityConfiguration.class,
        GlobalExceptionHandler.class
})
@TestPropertySource(properties = {
        "app.explorer.enabled=true",
        "app.security.enabled=true",
        "app.security.cognito.issuer-uri=https://issuer.example",
        "app.security.cognito.client-id=test-client"
})
class ExplorerControllerSecurityTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ExplorerService service;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    @Test
    void awardWithoutAuthenticationIsRejected() throws Exception {
        mockMvc.perform(
                        get("/api/v1/explorer/awards")
                                .param("awardNumber", "100012-00002")
                )
                .andExpect(status().isUnauthorized());
    }

    @Test
    void authenticatedUserCanReadAnAward() throws Exception {
        ExplorerAwardResponse award = new ExplorerAwardResponse(
                985585L, "100012-00002", 7, "Title", "Closed",
                "JOHN T CLARKE", "300940", null, "1203250000",
                "CAS SPACE PHYSICS", true
        );
        when(service.findAward("100012-00002")).thenReturn(award);

        mockMvc.perform(
                        get("/api/v1/explorer/awards")
                                .param("awardNumber", "100012-00002")
                                .with(jwt())
                )
                .andExpect(status().isOk());
    }
}
