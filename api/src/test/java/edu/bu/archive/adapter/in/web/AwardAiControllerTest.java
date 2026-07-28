package edu.bu.archive.adapter.in.web;

import edu.bu.archive.application.ai.AwardAiSummaryService;
import edu.bu.archive.application.ai.AiSummaryExecutionException;
import edu.bu.archive.config.SecurityConfiguration;
import edu.bu.archive.domain.model.ai.AiCitation;
import edu.bu.archive.domain.model.ai.AiResponse;
import edu.bu.archive.domain.model.ai.AwardAiSummaryResult;

import java.util.List;
import java.util.NoSuchElementException;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(AwardAiController.class)
@Import({
        SecurityConfiguration.class,
        AiExceptionHandler.class
})
@TestPropertySource(properties = {
        "app.ai.enabled=true",
        "app.security.enabled=true",
        "app.security.cognito.issuer-uri=https://issuer.example",
        "app.security.cognito.client-id=test-client"
})
class AwardAiControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private AwardAiSummaryService service;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    @Test
    void requiresAuthentication() throws Exception {
        mockMvc.perform(post("/api/ai/awards/A-100/summary"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void acceptsHyphenatedAwardNumberAndReturnsCorrelationId()
            throws Exception {
        UUID correlationId = UUID.fromString(
                "11111111-1111-1111-1111-111111111111"
        );
        when(service.summarize("A-100", "user-subject"))
                .thenReturn(
                        new AwardAiSummaryResult(
                                new AiResponse(
                                        "Award history summary",
                                        List.of(
                                                new AiCitation(
                                                        "award",
                                                        "101",
                                                        "A-100",
                                                        1
                                                )
                                        ),
                                        "stub",
                                        "deterministic-award-summary-v1",
                                        null,
                                        null
                                ),
                                correlationId
                        )
                );

        mockMvc.perform(
                        post("/api/ai/awards/A-100/summary")
                                .with(jwt().jwt(jwt ->
                                        jwt.subject("user-subject")
                                ))
                )
                .andExpect(status().isOk())
                .andExpect(
                        jsonPath("$.summary")
                                .value("Award history summary")
                )
                .andExpect(jsonPath("$.citations[0].recordType")
                        .value("award"))
                .andExpect(jsonPath("$.citations[0].recordId")
                        .value("101"))
                .andExpect(jsonPath("$.citations[0].awardNumber")
                        .value("A-100"))
                .andExpect(jsonPath("$.citations[0].sequenceNumber")
                        .value(1))
                .andExpect(jsonPath("$.provider").value("stub"))
                .andExpect(
                        jsonPath("$.model")
                                .value(
                                        "deterministic-award-summary-v1"
                                )
                )
                .andExpect(
                        jsonPath("$.correlationId")
                                .value(correlationId.toString())
                )
                .andExpect(jsonPath("$.systemPrompt").doesNotExist())
                .andExpect(jsonPath("$.context").doesNotExist())
                .andExpect(jsonPath("$.diagnostics").doesNotExist());

        verify(service).summarize("A-100", "user-subject");
    }

    @Test
    void returnsSafeNotFoundForAMissingAward()
            throws Exception {
        UUID correlationId = UUID.fromString(
                "22222222-2222-2222-2222-222222222222"
        );
        when(service.summarize("UNKNOWN", "user-subject"))
                .thenThrow(
                        new AiSummaryExecutionException(
                                correlationId,
                                new NoSuchElementException(
                                        "Award not found: UNKNOWN"
                                )
                        )
                );

        mockMvc.perform(
                        post("/api/ai/awards/UNKNOWN/summary")
                                .with(jwt().jwt(jwt ->
                                        jwt.subject("user-subject")
                                ))
                )
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(
                        jsonPath("$.message")
                                .value("Award not found: UNKNOWN")
                )
                .andExpect(
                        jsonPath("$.provider").doesNotExist()
                )
                .andExpect(
                        jsonPath("$.correlationId")
                                .value(correlationId.toString())
                );
    }

    @Test
    void rejectsARequestBody()
            throws Exception {
        mockMvc.perform(
                        post("/api/ai/awards/A-100/summary")
                                .with(jwt().jwt(jwt ->
                                        jwt.subject("user-subject")
                                ))
                                .contentType("application/json")
                                .content("{}")
                )
                .andExpect(status().isBadRequest())
                .andExpect(
                        jsonPath("$.message")
                                .value("Request body is not allowed")
                );
    }
}
