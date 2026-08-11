package edu.bu.archive.adapter.in.web;

import edu.bu.archive.adapter.in.web.dto.ai.AwardEvidenceResultResponse;
import edu.bu.archive.adapter.in.web.dto.ai.AwardEvidenceSearchResponse;
import edu.bu.archive.application.ai.AwardEvidenceSearchException;
import edu.bu.archive.application.ai.AwardEvidenceSearchService;
import edu.bu.archive.config.SecurityConfiguration;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.NoSuchElementException;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/*
 * Proves POST /api/ai/awards/{awardNumber}/evidence-search is covered
 * by the same blanket .requestMatchers("/api/**").authenticated() rule
 * every other /api/** route uses - mirrors
 * ExplorerControllerSecurityTest's exact pattern. app.search.semantic.enabled
 * must be true here for the @ConditionalOnProperty-gated controller
 * bean to even register - proving, as a side effect, that this
 * endpoint is independent of app.ai.enabled (never set in this test).
 */
@WebMvcTest(AwardEvidenceSearchController.class)
@Import({
        SecurityConfiguration.class,
        AiExceptionHandler.class
})
@TestPropertySource(properties = {
        "app.search.semantic.enabled=true",
        "app.security.enabled=true",
        "app.security.cognito.issuer-uri=https://issuer.example",
        "app.security.cognito.client-id=test-client"
})
class AwardEvidenceSearchControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private AwardEvidenceSearchService service;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    private String requestBody(String query) throws Exception {
        return objectMapper.writeValueAsString(
                java.util.Map.of("query", query)
        );
    }

    // --- Authentication required ---

    @Test
    void evidenceSearchWithoutAuthenticationIsRejected() throws Exception {
        mockMvc.perform(
                        post("/api/ai/awards/204713-00001/evidence-search")
                                .contentType("application/json")
                                .content(requestBody("Which proposal is connected to this Award?"))
                )
                .andExpect(status().isUnauthorized());
    }

    @Test
    void authenticatedUserCanSearchEvidence() throws Exception {
        when(service.search(
                eq("204713-00001"), anyString(), anyList(), any()
        )).thenReturn(new AwardEvidenceSearchResponse(
                "Which proposal is connected to this Award?",
                "204713-00001",
                List.of(new AwardEvidenceResultResponse(
                        "RELATED_PROPOSAL", "204713-00001",
                        "Related Proposal",
                        "Award 204713-00001 version 1 is funded by Proposal 01128961: CARB-X.",
                        "archive.award_funding_proposal", "1768708",
                        0.91, "fundingProposals"
                )),
                false,
                UUID.randomUUID().toString()
        ));

        mockMvc.perform(
                        post("/api/ai/awards/204713-00001/evidence-search")
                                .contentType("application/json")
                                .content(requestBody("Which proposal is connected to this Award?"))
                                .with(jwt())
                )
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.awardNumber").value("204713-00001"))
                .andExpect(jsonPath("$.results[0].documentType").value("RELATED_PROPOSAL"))
                .andExpect(jsonPath("$.results[0].sourcePrimaryKey").value("1768708"))
                .andExpect(jsonPath("$.results[0].excerpt").value(org.hamcrest.Matchers.containsString("01128961")))
                .andExpect(jsonPath("$.insufficientEvidence").value(false));
    }

    // --- Empty query rejected by request validation ---

    @Test
    void blankQueryIsRejected() throws Exception {
        mockMvc.perform(
                        post("/api/ai/awards/204713-00001/evidence-search")
                                .contentType("application/json")
                                .content(requestBody(""))
                                .with(jwt())
                )
                .andExpect(status().isBadRequest());
    }

    // --- Missing Award ---

    @Test
    void missingAwardReturnsNotFound() throws Exception {
        UUID correlationId = UUID.randomUUID();
        when(service.search(
                eq("NO-SUCH-AWARD"), anyString(), anyList(), any()
        )).thenThrow(new AwardEvidenceSearchException(
                correlationId,
                new NoSuchElementException("Award not found: NO-SUCH-AWARD")
        ));

        mockMvc.perform(
                        post("/api/ai/awards/NO-SUCH-AWARD/evidence-search")
                                .contentType("application/json")
                                .content(requestBody("query"))
                                .with(jwt())
                )
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.correlationId").value(correlationId.toString()));
    }

    // --- Invalid evidence type ---

    @Test
    void invalidEvidenceTypeReturnsBadRequest() throws Exception {
        UUID correlationId = UUID.randomUUID();
        when(service.search(
                eq("204713-00001"), anyString(), anyList(), any()
        )).thenThrow(new AwardEvidenceSearchException(
                correlationId,
                new IllegalArgumentException("Not an approved evidence type: AWARD_ATTACHMENT")
        ));

        mockMvc.perform(
                        post("/api/ai/awards/204713-00001/evidence-search")
                                .contentType("application/json")
                                .content(objectMapper.writeValueAsString(
                                        java.util.Map.of(
                                                "query", "query",
                                                "documentTypes", List.of("AWARD_ATTACHMENT")
                                        )
                                ))
                                .with(jwt())
                )
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("AWARD_ATTACHMENT")
                ));
    }

    // --- Provider unavailable ---

    @Test
    void providerFailureReturnsServiceUnavailable() throws Exception {
        UUID correlationId = UUID.randomUUID();
        when(service.search(
                eq("204713-00001"), anyString(), anyList(), any()
        )).thenThrow(new AwardEvidenceSearchException(
                correlationId,
                new edu.bu.archive.adapter.out.search.EmbeddingProviderException(
                        "Failed to embed query text via Bedrock",
                        new RuntimeException("boom")
                )
        ));

        mockMvc.perform(
                        post("/api/ai/awards/204713-00001/evidence-search")
                                .contentType("application/json")
                                .content(requestBody("query"))
                                .with(jwt())
                )
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.message").value(
                        "Evidence search is temporarily unavailable"
                ))
                .andExpect(jsonPath("$.correlationId").value(correlationId.toString()));
    }

    // --- Insufficient evidence is a 200, not an error ---

    @Test
    void noIndexedEvidenceReturnsOkWithInsufficientEvidenceTrue() throws Exception {
        when(service.search(
                eq("204713-00001"), anyString(), anyList(), any()
        )).thenReturn(new AwardEvidenceSearchResponse(
                "query", "204713-00001", List.of(), true,
                UUID.randomUUID().toString()
        ));

        mockMvc.perform(
                        post("/api/ai/awards/204713-00001/evidence-search")
                                .contentType("application/json")
                                .content(requestBody("query"))
                                .with(jwt())
                )
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.insufficientEvidence").value(true))
                .andExpect(jsonPath("$.results").isArray())
                .andExpect(jsonPath("$.results").isEmpty());
    }
}
