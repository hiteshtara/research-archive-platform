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

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/*
 * QA TC-017 / defect QA-D2. "smith%00cohort" in a search URL used to
 * reach PostgreSQL, which rejects a NUL byte at the UTF-8 decoder
 * ("invalid byte sequence for encoding "UTF8": 0x00"); the resulting
 * DataAccessException had no handler, so all five module searches
 * answered HTTP 500 on what is purely bad client input. Reproduced
 * against a real PostgreSQL before this fix was written.
 *
 * These assertions pin the behaviour that replaced it: one consistent
 * 400 VALIDATION_ERROR from every search endpoint, raised before the
 * service layer is touched at all, with ordinary queries - including
 * the application's own wildcard and literal ILIKE metacharacters -
 * still passing straight through.
 */
@WebMvcTest(controllers = {
        AwardV1Controller.class,
        ProposalArchiveController.class,
        NegotiationArchiveController.class,
        SubawardArchiveController.class
})
@Import({
        LocalSecurityConfiguration.class,
        WebRequestValidationConfiguration.class,
        GlobalExceptionHandler.class
})
@TestPropertySource(properties = "app.security.enabled=false")
class SearchControlCharacterValidationTest {

    private static final String NUL = String.valueOf((char) 0);

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

    /*
     * The five searches TC-017 found returning 500, each with its own
     * free-text parameter name.
     */
    @ParameterizedTest(name = "{0} rejects a NUL byte with 400 VALIDATION_ERROR")
    @CsvSource({
            "/api/v1/awards/search,          q",
            "/api/v1/awards/versions/search, q",
            "/api/proposals/search,          query",
            "/api/negotiations,              query",
            "/api/subawards,                 query"
    })
    void everySearchRejectsANulByteConsistently(String path, String parameter) throws Exception {
        mockMvc.perform(get(path.trim()).param(parameter.trim(), "smith" + NUL + "cohort"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("U+0000")))
                .andExpect(jsonPath("$.path").value(path.trim()));
    }

    @Test
    void theNulByteIsRefusedBeforeTheServiceLayerIsReached() throws Exception {
        mockMvc.perform(get("/api/v1/awards/search").param("q", "smith" + NUL + "cohort"))
                .andExpect(status().isBadRequest());

        // The point of fixing this at the boundary: the value never
        // becomes a bound parameter, so it can never reach PostgreSQL.
        verify(awardService, never()).search(anyString(), any(), anyInt(), anyInt());
    }

    @Test
    void aStructuredFilterParameterIsCheckedToNotOnlyTheFreeTextQuery() throws Exception {
        // The same byte in any other parameter would have reached the
        // database by the identical route.
        mockMvc.perform(get("/api/v1/awards/search").param("sponsor", "NSF" + NUL))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("sponsor")));
    }

    @Test
    void theErrorNamesTheParameterButNeverEchoesTheValueBack() throws Exception {
        mockMvc.perform(get("/api/v1/awards/search").param("q", "secret" + NUL))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("'q'")))
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.not(
                                org.hamcrest.Matchers.containsString("secret"))));
    }

    /*
     * The other half of TC-017: everything that was already handled
     * safely must keep working. These reach the (mocked) service, which
     * is exactly the point - they are accepted, not refused.
     */
    @ParameterizedTest(name = "an ordinary query is still accepted: {0}")
    @CsvSource({
            "105698",
            "*105698*",
            "50%",
            "A_B",
            "smith"
    })
    void ordinaryAndWildcardQueriesAreStillAccepted(String query) throws Exception {
        mockMvc.perform(get("/api/v1/awards/search").param("q", query))
                .andExpect(status().isOk());
    }

    @Test
    void injectionShapedTextIsStillAcceptedAndStillInert() throws Exception {
        mockMvc.perform(get("/api/v1/awards/search")
                        .param("q", "'; DROP TABLE archive.award_version; --"))
                .andExpect(status().isOk());
    }
}
