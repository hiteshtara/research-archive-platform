package edu.bu.archive.adapter.in.web;

import edu.bu.archive.application.ai.AwardAiQuestionService;
import edu.bu.archive.config.SecurityConfiguration;
import edu.bu.archive.domain.model.ai.AiCitation;
import edu.bu.archive.domain.model.ai.AwardQuestionResult;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
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

@WebMvcTest(AwardAiQuestionController.class)
@Import({
        SecurityConfiguration.class,
        AiExceptionHandler.class
})
@TestPropertySource(properties = {
        "app.ai.enabled=true",
        "app.ai.questions-enabled=true",
        "app.security.enabled=true",
        "app.security.cognito.issuer-uri=https://issuer.example",
        "app.security.cognito.client-id=test-client"
})
class AwardAiQuestionControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private AwardAiQuestionService service;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    @Test
    void requiresAuthentication() throws Exception {
        mockMvc.perform(post(
                        "/api/ai/awards/A-100/questions"
                )
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"question":"What is the current status?"}
                                """))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void acceptsQuestionAndReturnsStructuredAnswer()
            throws Exception {
        UUID correlationId = UUID.fromString(
                "11111111-1111-1111-1111-111111111111"
        );
        when(service.answer(
                "A-100",
                "What is the current status?",
                "user-subject"
        )).thenReturn(new AwardQuestionResult(
                "The current archived Award status is Closed.",
                "deterministic_fact",
                List.of(new AiCitation(
                        "award", "101", "A-100", 2
                )),
                "deterministic",
                "none",
                correlationId
        ));

        mockMvc.perform(post(
                        "/api/ai/awards/A-100/questions"
                )
                        .with(jwt().jwt(token ->
                                token.subject("user-subject")
                        ))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"question":"What is the current status?"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.answer")
                        .value(
                                "The current archived Award status "
                                        + "is Closed."
                        ))
                .andExpect(jsonPath("$.answerType")
                        .value("deterministic_fact"))
                .andExpect(jsonPath("$.citations[0].recordId")
                        .value("101"))
                .andExpect(jsonPath("$.provider")
                        .value("deterministic"))
                .andExpect(jsonPath("$.model").value("none"))
                .andExpect(jsonPath("$.correlationId")
                        .value(correlationId.toString()))
                .andExpect(jsonPath("$.question").doesNotExist())
                .andExpect(jsonPath("$.context").doesNotExist());

        verify(service).answer(
                "A-100",
                "What is the current status?",
                "user-subject"
        );
    }

    @Test
    void rejectsBlankAndOversizedQuestions() throws Exception {
        mockMvc.perform(post(
                        "/api/ai/awards/A-100/questions"
                )
                        .with(jwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\":\" \"}"))
                .andExpect(status().isBadRequest());

        mockMvc.perform(post(
                        "/api/ai/awards/A-100/questions"
                )
                        .with(jwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\":\""
                                + "x".repeat(501)
                                + "\"}"))
                .andExpect(status().isBadRequest());
    }
}
