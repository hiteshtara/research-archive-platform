package edu.bu.archive.adapter.in.web;

import edu.bu.archive.application.award.ExplorerService;
import edu.bu.archive.application.security.AttachmentAuthorizationService;
import edu.bu.archive.config.SecurityConfiguration;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/*
 * @NotBlank/@Positive @RequestParam rejection needs a real Spring-managed
 * bean: @Validated method-parameter constraints are enforced by an AOP
 * proxy that Spring Boot's ValidationAutoConfiguration wraps around the
 * bean (MethodValidationPostProcessor) - a proxy that only exists when the
 * controller is created through a Spring ApplicationContext, not when
 * constructed directly (as ExplorerControllerTest does via
 * MockMvcBuilders.standaloneSetup(new ExplorerController(...))). Confirmed
 * empirically: the same two assertions returned 200 under standaloneSetup.
 * app.explorer.enabled=true is required here too, since ExplorerController
 * only registers as a bean when that property is true.
 */
@WebMvcTest(ExplorerController.class)
@Import({
        SecurityConfiguration.class,
        GlobalExceptionHandler.class,
        AttachmentAuthorizationService.class
})
@TestPropertySource(properties = {
        "app.explorer.enabled=true",
        "app.security.enabled=true",
        "app.security.cognito.issuer-uri=https://issuer.example",
        "app.security.cognito.client-id=test-client"
})
class ExplorerControllerValidationTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ExplorerService service;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    @Test
    void awardRejectsABlankAwardNumber() throws Exception {
        mockMvc.perform(
                        get("/api/v1/explorer/awards")
                                .param("awardNumber", " ")
                                .with(jwt())
                )
                .andExpect(status().isBadRequest());
    }

    @Test
    void awardVersionRejectsANonPositiveAwardId() throws Exception {
        mockMvc.perform(
                        get("/api/v1/explorer/award-versions")
                                .param("awardId", "0")
                                .with(jwt())
                )
                .andExpect(status().isBadRequest());
    }

    @Test
    void attachmentsRejectsANonPositiveAwardId() throws Exception {
        mockMvc.perform(
                        get("/api/v1/explorer/attachments")
                                .param("awardId", "-1")
                                .with(jwt())
                )
                .andExpect(status().isBadRequest());
    }

    @Test
    void unitRejectsABlankUnitNumber() throws Exception {
        mockMvc.perform(
                        get("/api/v1/explorer/units")
                                .param("unitNumber", " ")
                                .with(jwt())
                )
                .andExpect(status().isBadRequest());
    }
}
