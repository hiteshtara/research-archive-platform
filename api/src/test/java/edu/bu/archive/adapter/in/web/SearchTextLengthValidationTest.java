package edu.bu.archive.adapter.in.web;

import edu.bu.archive.adapter.out.persistence.ProposalArchiveRepository;
import edu.bu.archive.application.award.AwardArchiveService;
import edu.bu.archive.application.award.AwardContactService;
import edu.bu.archive.application.award.report.AwardConsolidatedReportAssembler;
import edu.bu.archive.application.award.report.AwardReportPdfRenderer;
import edu.bu.archive.application.award.report.AwardReportService;
import edu.bu.archive.application.negotiation.NegotiationArchiveService;
import edu.bu.archive.application.proposal.ProposalArchiveService;
import edu.bu.archive.application.security.AttachmentAuthorizationService;
import edu.bu.archive.application.service.GlobalSearchService;
import edu.bu.archive.application.subaward.SubawardArchiveService;
import edu.bu.archive.config.LocalSecurityConfiguration;
import edu.bu.archive.config.WebRequestValidationConfiguration;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/*
 * QA TC-018. Global Search enforced a 200-character limit and every
 * module search accepted any length, so the same query was fine on one
 * page and slow on another (2,000 characters took about 2.2s, 6,000
 * about 6.4s, on a small dataset). The limit is now shared.
 *
 * Two things these tests are really guarding:
 *
 *  1. The boundary is inclusive. Exactly 200 must still search, or the
 *     limit quietly becomes 199 and a legitimate query starts failing.
 *
 *  2. @Size does nothing without @Validated on the controller.
 *     ProposalArchiveController and NegotiationArchiveController had no
 *     @Validated at all, so an annotation added there alone would have
 *     been silently inert - the test would pass by accident only if it
 *     asserted the 200 case. Every endpoint is therefore exercised for
 *     both sides of the boundary.
 */
@WebMvcTest(controllers = {
        AwardV1Controller.class,
        ProposalArchiveController.class,
        NegotiationArchiveController.class,
        SubawardArchiveController.class,
        GlobalSearchController.class
})
@Import({
        LocalSecurityConfiguration.class,
        WebRequestValidationConfiguration.class,
        GlobalExceptionHandler.class
})
@TestPropertySource(properties = "app.security.enabled=false")
class SearchTextLengthValidationTest {

    private static final String AT_LIMIT =
            "a".repeat(SearchTextLimits.MAX_SEARCH_TEXT_LENGTH);
    private static final String OVER_LIMIT =
            "a".repeat(SearchTextLimits.MAX_SEARCH_TEXT_LENGTH + 1);

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private AwardArchiveService awardService;

    @MockitoBean
    private AwardContactService awardContactService;

    @MockitoBean
    private AttachmentAuthorizationService attachmentAuthorizationService;

    @MockitoBean
    private AwardReportService awardReportService;

    @MockitoBean
    private AwardReportPdfRenderer awardReportPdfRenderer;

    @MockitoBean
    private AwardConsolidatedReportAssembler consolidatedReportAssembler;

    @MockitoBean
    private ProposalArchiveService proposalService;

    @MockitoBean
    private ProposalArchiveRepository proposalRepository;

    @MockitoBean
    private NegotiationArchiveService negotiationService;

    @MockitoBean
    private SubawardArchiveService subawardService;

    @MockitoBean
    private GlobalSearchService globalSearchService;

    @ParameterizedTest(name = "{0} refuses a query one character over the limit")
    @CsvSource({
            "/api/v1/awards/search,          q",
            "/api/v1/awards/versions/search, q",
            "/api/proposals/search,          query",
            "/api/proposals/families,        query",
            "/api/negotiations,              query",
            "/api/subawards,                 query",
            "/api/global-search,             query"
    })
    void aQueryOverTheLimitIsRefused(String path, String parameter) throws Exception {
        mockMvc.perform(get(path.trim()).param(parameter.trim(), OVER_LIMIT))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    @ParameterizedTest(name = "{0} still accepts a query of exactly the limit")
    @CsvSource({
            "/api/v1/awards/search,          q",
            "/api/v1/awards/versions/search, q",
            "/api/proposals/search,          query",
            "/api/proposals/families,        query",
            "/api/negotiations,              query",
            "/api/subawards,                 query",
            "/api/global-search,             query"
    })
    void aQueryOfExactlyTheLimitIsAccepted(String path, String parameter) throws Exception {
        // The boundary is inclusive. If this fails the limit has become
        // 199 for that endpoint.
        mockMvc.perform(get(path.trim()).param(parameter.trim(), AT_LIMIT))
                .andExpect(status().isOk());
    }

    @ParameterizedTest(name = "{0} still runs an ordinary search")
    @CsvSource({
            "/api/v1/awards/search,          q,     105698",
            "/api/v1/awards/versions/search, q,     105698",
            "/api/proposals/search,          query, smith",
            "/api/negotiations,              query, agreement",
            "/api/subawards,                 query, 1970",
            "/api/global-search,             query, autism"
    })
    void ordinarySearchesAreUnaffected(String path, String parameter, String value)
            throws Exception {
        mockMvc.perform(get(path.trim()).param(parameter.trim(), value.trim()))
                .andExpect(status().isOk());
    }

    @Test
    void theApplicationsOwnWildcardSyntaxStillSearches() throws Exception {
        mockMvc.perform(get("/api/v1/awards/search").param("q", "*105698*"))
                .andExpect(status().isOk());
    }

    @Test
    void globalSearchKeepsItsOwnMinimumLength() throws Exception {
        // Unchanged by this work: Global Search fans out across every
        // module, so it still refuses a one-character query. No module
        // search gains a minimum - an empty query there means "list
        // everything", which stays valid.
        mockMvc.perform(get("/api/global-search").param("query", "a"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));

        mockMvc.perform(get("/api/global-search").param("query", "ab"))
                .andExpect(status().isOk());
    }

    @Test
    void aModuleSearchStillAcceptsAnEmptyQuery() throws Exception {
        mockMvc.perform(get("/api/v1/awards/search").param("q", ""))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/awards/search"))
                .andExpect(status().isOk());
    }

    @Test
    void anAstralCharacterCountsTheSameTwoUnitsAsInTheBrowser() throws Exception {
        // An emoji is two UTF-16 code units to Java's String.length()
        // and to JavaScript's String.length, so 100 of them sit exactly
        // on the limit and 101 go over - the same verdict the browser
        // reaches before the request is made.
        String emoji = "😀";
        mockMvc.perform(get("/api/v1/awards/search").param("q", emoji.repeat(100)))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/awards/search").param("q", emoji.repeat(101)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    @Test
    void anOverLongQueryIsRefusedBeforeTheServiceLayerIsReached() throws Exception {
        mockMvc.perform(get("/api/v1/awards/search").param("q", OVER_LIMIT))
                .andExpect(status().isBadRequest());

        org.mockito.Mockito.verify(awardService, org.mockito.Mockito.never())
                .search(org.mockito.ArgumentMatchers.anyString(),
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.anyInt(),
                        org.mockito.ArgumentMatchers.anyInt());
    }

    @Test
    void aControlCharacterIsStillRefusedTheSameWay() throws Exception {
        // TC-017's handling must survive this change: the length check
        // and the character check answer with the same code, and a
        // short query carrying a NUL is still refused.
        mockMvc.perform(get("/api/v1/awards/search").param("q", "smith" + (char) 0 + "cohort"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }
}
