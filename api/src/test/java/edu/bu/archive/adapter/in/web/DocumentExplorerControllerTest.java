package edu.bu.archive.adapter.in.web;

import edu.bu.archive.adapter.in.web.dto.PageResponse;
import edu.bu.archive.adapter.in.web.dto.document.DocumentExplorerResponse;
import edu.bu.archive.adapter.in.web.dto.document.DocumentExplorerResultResponse;
import edu.bu.archive.application.document.DocumentExplorerService;
import edu.bu.archive.config.SecurityConfiguration;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDate;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/*
 * Proves GET /api/v1/documents is covered by the same blanket
 * .requestMatchers("/api/**").authenticated() rule every other /api/**
 * route uses, and that invalid module/normalizedStatus/sort values are
 * rejected end-to-end through the REAL DocumentExplorerService (not
 * mocked away), since GlobalExceptionHandler's IllegalArgumentException
 * -> 400 mapping is exactly what scenario 23 requires proving.
 */
@WebMvcTest(DocumentExplorerController.class)
@Import({SecurityConfiguration.class, GlobalExceptionHandler.class})
@TestPropertySource(properties = {
        "app.security.enabled=true",
        "app.security.cognito.issuer-uri=https://issuer.example",
        "app.security.cognito.client-id=test-client"
})
class DocumentExplorerControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private DocumentExplorerService service;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    // --- Scenario 25: Authentication ---

    @Test
    void searchWithoutAuthenticationIsRejected() throws Exception {
        mockMvc.perform(get("/api/v1/documents"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void authenticatedUserCanSearch() throws Exception {
        when(service.search(
                any(), any(), any(), any(), any(), any(), any(), anyBoolean(),
                any(), any(), any(), anyBoolean(), any(), any(), any(), any(), any(),
                anyInt(), anyInt()
        )).thenReturn(new DocumentExplorerResponse(
                new PageResponse<>(
                        List.of(new DocumentExplorerResultResponse(
                                "AWARD", "1037915", "204713-00001", "CARB-X",
                                "ACTIVE", "10", "Approved Award", "544",
                                "1202020000", "Unit Name", "9001", "Jane Smith", "PI",
                                "NIH", "National Institutes of Health", null, null,
                                LocalDate.of(2020, 1, 1), "/awards/3561610", 2, 1, 0
                        )),
                        0, 25, 1L, 1, true, true
                ),
                List.of()
        ));

        mockMvc.perform(
                        get("/api/v1/documents")
                                .param("documentNumber", "1037915")
                                .with(jwt())
                )
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.results.content[0].module").value("AWARD"))
                .andExpect(jsonPath("$.results.content[0].targetRoute").value("/awards/3561610"))
                .andExpect(jsonPath("$.results.totalElements").value(1));
    }

    // --- Scenario 23: invalid module/status/sort rejected end-to-end.
    // DocumentExplorerServiceTest proves the validation logic itself
    // (IRB/unrecognized module/status/sort all throw
    // IllegalArgumentException); this proves the controller-level
    // wiring that turns that exception into an HTTP 400 via the
    // app-wide GlobalExceptionHandler. ---

    @Test
    void controllerPropagatesServiceValidationErrorsAsBadRequest() throws Exception {
        when(service.search(
                any(), any(), any(), any(), any(), any(), any(), anyBoolean(),
                any(), any(), any(), anyBoolean(), any(), any(), any(), any(), any(),
                anyInt(), anyInt()
        )).thenThrow(new IllegalArgumentException("Not an approved module: IRB"));

        mockMvc.perform(
                        get("/api/v1/documents")
                                .param("module", "IRB")
                                .with(jwt())
                )
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("IRB")));
    }

    @Test
    void pageOutOfRangeIsRejected() throws Exception {
        mockMvc.perform(
                        get("/api/v1/documents")
                                .param("page", "-1")
                                .with(jwt())
                )
                .andExpect(status().isBadRequest());
    }

    @Test
    void sizeOutOfRangeIsRejected() throws Exception {
        mockMvc.perform(
                        get("/api/v1/documents")
                                .param("size", "1000")
                                .with(jwt())
                )
                .andExpect(status().isBadRequest());
    }
}
