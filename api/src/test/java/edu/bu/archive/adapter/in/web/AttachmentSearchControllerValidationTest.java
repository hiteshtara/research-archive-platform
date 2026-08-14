package edu.bu.archive.adapter.in.web;

import edu.bu.archive.application.security.AttachmentAuthorizationService;
import edu.bu.archive.application.service.AttachmentSearchService;
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
 * @Min/@Max @RequestParam rejection needs a real Spring-managed bean -
 * see ExplorerControllerValidationTest's header comment for why
 * standaloneSetup can't exercise this (no MethodValidationPostProcessor
 * proxy without a real ApplicationContext).
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
class AttachmentSearchControllerValidationTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private AttachmentSearchService service;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    @Test
    void searchRejectsAPageSizeAboveTheMaximumOfOneHundred() throws Exception {
        mockMvc.perform(
                        get("/api/v1/attachments/search")
                                .param("awardNumber", "200086-00001")
                                .param("size", "101")
                                .with(jwt())
                )
                .andExpect(status().isBadRequest());
    }

    @Test
    void searchRejectsANegativePage() throws Exception {
        mockMvc.perform(
                        get("/api/v1/attachments/search")
                                .param("awardNumber", "200086-00001")
                                .param("page", "-1")
                                .with(jwt())
                )
                .andExpect(status().isBadRequest());
    }

    @Test
    void searchRejectsAPageSizeOfZero() throws Exception {
        mockMvc.perform(
                        get("/api/v1/attachments/search")
                                .param("awardNumber", "200086-00001")
                                .param("size", "0")
                                .with(jwt())
                )
                .andExpect(status().isBadRequest());
    }
}
