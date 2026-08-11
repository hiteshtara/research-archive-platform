package edu.bu.archive.adapter.in.web;

import edu.bu.archive.adapter.in.web.dto.PageResponse;
import edu.bu.archive.adapter.in.web.dto.document.DocumentSearchResultResponse;
import edu.bu.archive.application.document.DocumentSearchService;
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
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/*
 * Proves GET /api/documents/search is covered by the same blanket
 * .requestMatchers("/api/**").authenticated() rule every other /api/**
 * route uses - mirrors ExplorerControllerSecurityTest and
 * AwardEvidenceSearchControllerTest's exact pattern.
 */
@WebMvcTest(DocumentSearchController.class)
@Import(SecurityConfiguration.class)
@TestPropertySource(properties = {
        "app.security.enabled=true",
        "app.security.cognito.issuer-uri=https://issuer.example",
        "app.security.cognito.client-id=test-client"
})
class DocumentSearchControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private DocumentSearchService service;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    @Test
    void searchWithoutAuthenticationIsRejected() throws Exception {
        mockMvc.perform(get("/api/documents/search"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void authenticatedUserCanSearchDocuments() throws Exception {
        when(service.search(any(), any(), any(), any(), any(), anyInt(), anyInt()))
                .thenReturn(new PageResponse<>(
                        List.of(new DocumentSearchResultResponse(
                                "AWARD", "1037915", "204713-00001", "CARB-X",
                                "Active", "544", LocalDate.of(2020, 1, 1),
                                "/awards/3561610"
                        )),
                        0, 25, 1L, 1, true, true
                ));

        mockMvc.perform(
                        get("/api/documents/search")
                                .param("documentNumber", "1037915")
                                .with(jwt())
                )
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].module").value("AWARD"))
                .andExpect(jsonPath("$.content[0].documentNumber").value("1037915"))
                .andExpect(jsonPath("$.content[0].businessRecordNumber").value("204713-00001"))
                .andExpect(jsonPath("$.content[0].targetRoute").value("/awards/3561610"))
                .andExpect(jsonPath("$.totalElements").value(1));
    }

    @Test
    void emptyResultsReturnOkWithEmptyContent() throws Exception {
        when(service.search(any(), any(), any(), any(), any(), anyInt(), anyInt()))
                .thenReturn(new PageResponse<>(List.of(), 0, 25, 0L, 0, true, true));

        mockMvc.perform(
                        get("/api/documents/search")
                                .param("documentNumber", "no-such-document")
                                .with(jwt())
                )
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isArray())
                .andExpect(jsonPath("$.content").isEmpty())
                .andExpect(jsonPath("$.totalElements").value(0));
    }

    @Test
    void moduleFilterIsPassedThrough() throws Exception {
        when(service.search(any(), eq("PROPOSAL"), any(), any(), any(), anyInt(), anyInt()))
                .thenReturn(new PageResponse<>(List.of(), 0, 25, 0L, 0, true, true));

        mockMvc.perform(
                        get("/api/documents/search")
                                .param("module", "PROPOSAL")
                                .with(jwt())
                )
                .andExpect(status().isOk());
    }

    @Test
    void pageOutOfRangeIsRejected() throws Exception {
        mockMvc.perform(
                        get("/api/documents/search")
                                .param("page", "-1")
                                .with(jwt())
                )
                .andExpect(status().isBadRequest());
    }

    @Test
    void sizeOutOfRangeIsRejected() throws Exception {
        mockMvc.perform(
                        get("/api/documents/search")
                                .param("size", "1000")
                                .with(jwt())
                )
                .andExpect(status().isBadRequest());
    }
}
