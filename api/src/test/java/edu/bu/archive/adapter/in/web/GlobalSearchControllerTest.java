package edu.bu.archive.adapter.in.web;

import edu.bu.archive.adapter.in.web.dto.GlobalSearchItemResponse;
import edu.bu.archive.adapter.in.web.dto.GlobalSearchResponse;
import edu.bu.archive.application.service.GlobalSearchService;
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

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/*
 * HTTP-layer routing/validation tests for GlobalSearchController - the
 * controller itself does no fan-out/mapping work, only delegates to
 * GlobalSearchService (see GlobalSearchServiceTest for the real
 * orchestration coverage). One endpoint, fanned out inside the API -
 * the frontend never issues a per-domain request.
 *
 * @WebMvcTest (not standaloneSetup) is required here, the same
 * precedent ExplorerControllerValidationTest already established:
 * @Validated method-parameter constraints (@NotBlank/@Size on
 * @RequestParam) are only enforced by the AOP proxy Spring Boot's
 * ValidationAutoConfiguration wraps around a real Spring-managed bean -
 * standaloneSetup constructs the controller directly and silently
 * skips that validation.
 */
@WebMvcTest(GlobalSearchController.class)
@Import({
        SecurityConfiguration.class,
        GlobalExceptionHandler.class
})
@TestPropertySource(properties = {
        "app.security.enabled=true",
        "app.security.cognito.issuer-uri=https://issuer.example",
        "app.security.cognito.client-id=test-client"
})
class GlobalSearchControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private GlobalSearchService service;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    @Test
    void searchIsRoutedUnderTheGlobalSearchPrefixAndDelegatesToTheService() throws Exception {
        GlobalSearchItemResponse item = new GlobalSearchItemResponse(
                "AWARD", 3831872L, "103692-00002", "Cancer Research Grant",
                "NIH", "Active", "1054966", "Workflow Document Number",
                "1054966", "/awards/3831872", null, 3831872L, 46, null, null,
                null
        );
        GlobalSearchResponse response = new GlobalSearchResponse(
                "campbell", 1, List.of(item), List.of()
        );
        when(service.search("campbell")).thenReturn(response);

        mockMvc.perform(
                        get("/api/global-search").param("query", "campbell").with(jwt())
                )
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.query").value("campbell"))
                .andExpect(jsonPath("$.totalResults").value(1))
                .andExpect(jsonPath("$.results[0].module").value("AWARD"))
                .andExpect(jsonPath("$.results[0].route").value("/awards/3831872"))
                .andExpect(jsonPath("$.results[0].documentNumber").value("1054966"))
                .andExpect(jsonPath("$.results[0].matchedField").value("Workflow Document Number"))
                .andExpect(jsonPath("$.failedModules").isEmpty());

        verify(service).search("campbell");
    }

    @Test
    void surfacesFailedModulesWhenOneDomainSearchFailed() throws Exception {
        when(service.search("campbell")).thenReturn(
                new GlobalSearchResponse("campbell", 0, List.of(), List.of("AWARD"))
        );

        mockMvc.perform(
                        get("/api/global-search").param("query", "campbell").with(jwt())
                )
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.failedModules[0]").value("AWARD"));
    }

    @Test
    void rejectsAMissingQueryParameter() throws Exception {
        mockMvc.perform(get("/api/global-search").with(jwt()))
                .andExpect(status().isBadRequest());
    }

    @Test
    void rejectsAQueryShorterThanTwoCharacters() throws Exception {
        mockMvc.perform(
                        get("/api/global-search").param("query", "a").with(jwt())
                )
                .andExpect(status().isBadRequest());
    }

    @Test
    void rejectsABlankQuery() throws Exception {
        mockMvc.perform(
                        get("/api/global-search").param("query", "   ").with(jwt())
                )
                .andExpect(status().isBadRequest());
    }
}
