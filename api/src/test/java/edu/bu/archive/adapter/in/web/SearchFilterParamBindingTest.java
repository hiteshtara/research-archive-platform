package edu.bu.archive.adapter.in.web;

import edu.bu.archive.adapter.in.web.dto.GlobalSearchResponse;
import edu.bu.archive.adapter.in.web.dto.PageResponse;
import edu.bu.archive.adapter.in.web.dto.award.AwardSearchResponse;
import edu.bu.archive.adapter.in.web.dto.subaward.SubawardPageResponse;
import edu.bu.archive.adapter.out.persistence.ProposalArchiveRepository;
import edu.bu.archive.application.award.AwardArchiveService;
import edu.bu.archive.application.award.AwardContactService;
import edu.bu.archive.application.award.AwardSearchFilters;
import edu.bu.archive.application.award.report.AwardConsolidatedReportAssembler;
import edu.bu.archive.application.award.report.AwardReportPdfRenderer;
import edu.bu.archive.application.award.report.AwardReportService;
import edu.bu.archive.application.proposal.ProposalArchiveService;
import edu.bu.archive.application.proposal.ProposalSearchFilters;
import edu.bu.archive.application.security.AttachmentAuthorizationService;
import edu.bu.archive.application.service.GlobalSearchService;
import edu.bu.archive.application.subaward.SubawardArchiveService;
import edu.bu.archive.application.subaward.SubawardSearchFilters;

import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/*
 * The structured search filters travel from query parameters into each
 * module's filter record unchanged - the request-serialization half of
 * the shared filter contract (the UI half is covered by the
 * presentation tests under ui/src/features).
 */
class SearchFilterParamBindingTest {

    private static MockMvc mvc(Object controller) {
        return MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    private static AwardV1Controller awardController(AwardArchiveService service) {
        return new AwardV1Controller(
                service, mock(AwardContactService.class),
                mock(AttachmentAuthorizationService.class),
                mock(AwardReportService.class), mock(AwardReportPdfRenderer.class),
                mock(AwardConsolidatedReportAssembler.class));
    }

    @Test
    void awardSearchBindsEveryFilter() throws Exception {
        AwardArchiveService service = mock(AwardArchiveService.class);
        AwardSearchFilters expected = new AwardSearchFilters(
                "Active", "NIH", "Smith", "Chemistry",
                LocalDate.of(2020, 1, 1), LocalDate.of(2020, 12, 31));
        when(service.search("cancer", expected, 2, 25)).thenReturn(new AwardSearchResponse(
                null, new PageResponse<>(List.of(), 2, 25, 0, 0, false, true)));

        mvc(awardController(service)).perform(get("/api/v1/awards/search")
                        .param("q", "cancer")
                        .param("status", "Active")
                        .param("sponsor", "NIH")
                        .param("principalInvestigator", "Smith")
                        .param("leadUnit", "Chemistry")
                        .param("projectStartDateFrom", "2020-01-01")
                        .param("projectStartDateTo", "2020-12-31")
                        .param("page", "2"))
                .andExpect(status().isOk());

        verify(service).search("cancer", expected, 2, 25);
    }

    @Test
    void awardVersionSearchBindsFiltersAlongsideItsExactIdentifiers() throws Exception {
        AwardArchiveService service = mock(AwardArchiveService.class);
        AwardSearchFilters expected = new AwardSearchFilters(
                null, "NIH", null, null, null, LocalDate.of(2019, 6, 30));
        when(service.searchVersions(null, "200086-00001", null, null, "historical",
                expected, "date", 0, 25))
                .thenReturn(new PageResponse<>(List.of(), 0, 25, 0, 0, true, true));

        mvc(awardController(service)).perform(get("/api/v1/awards/versions/search")
                        .param("awardNumber", "200086-00001")
                        .param("versionFilter", "historical")
                        .param("sort", "date")
                        .param("sponsor", "NIH")
                        .param("projectStartDateTo", "2019-06-30"))
                .andExpect(status().isOk());

        verify(service).searchVersions(null, "200086-00001", null, null, "historical",
                expected, "date", 0, 25);
    }

    @Test
    void aMalformedDateIsA400NotAServerError() throws Exception {
        AwardArchiveService service = mock(AwardArchiveService.class);

        mvc(awardController(service)).perform(get("/api/v1/awards/search")
                        .param("projectStartDateFrom", "31/12/2020"))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(service);
    }

    @Test
    void subawardSearchBindsEveryFilter() throws Exception {
        SubawardArchiveService service = mock(SubawardArchiveService.class);
        SubawardSearchFilters expected = new SubawardSearchFilters(
                "Executed", "Cancer", "ORG-1",
                LocalDate.of(2019, 1, 1), LocalDate.of(2019, 12, 31),
                LocalDate.of(2020, 1, 1), LocalDate.of(2024, 6, 30));
        when(service.findPage("4500002829", expected, 0, 25))
                .thenReturn(new SubawardPageResponse(List.of(), 0, 25, 0, 0, true, true));

        mvc(new SubawardArchiveController(service, mock(AttachmentAuthorizationService.class)))
                .perform(get("/api/subawards")
                        .param("query", "4500002829")
                        .param("status", "Executed")
                        .param("sponsor", "Cancer")
                        .param("organizationId", "ORG-1")
                        .param("startDateFrom", "2019-01-01")
                        .param("startDateTo", "2019-12-31")
                        .param("endDateFrom", "2020-01-01")
                        .param("endDateTo", "2024-06-30"))
                .andExpect(status().isOk());

        verify(service).findPage("4500002829", expected, 0, 25);
    }

    @Test
    void proposalSearchIsPagedAndBindsItsFilters() throws Exception {
        ProposalArchiveService service = mock(ProposalArchiveService.class);
        ProposalSearchFilters expected = new ProposalSearchFilters("NIH", "Smith", "Chemistry");
        when(service.findFamilyPage("cancer", expected, 3, 25))
                .thenReturn(new PageResponse<>(List.of(), 3, 25, 0, 0, false, true));

        mvc(new ProposalArchiveController(service, mock(ProposalArchiveRepository.class)))
                .perform(get("/api/proposals/search")
                        .param("query", "cancer")
                        .param("sponsor", "NIH")
                        .param("principalInvestigator", "Smith")
                        .param("leadUnit", "Chemistry")
                        .param("page", "3"))
                .andExpect(status().isOk());

        verify(service).findFamilyPage("cancer", expected, 3, 25);
    }

    @Test
    void globalSearchAcceptsCommaSeparatedAndRepeatedRecordTypes() throws Exception {
        GlobalSearchService service = mock(GlobalSearchService.class);
        when(service.search(anyString(), any())).thenReturn(
                new GlobalSearchResponse("campbell", 0, List.of(), List.of()));

        mvc(new GlobalSearchController(service)).perform(get("/api/global-search")
                        .param("query", "campbell")
                        .param("modules", "AWARD,PROPOSAL")
                        .param("modules", "SUBAWARD"))
                .andExpect(status().isOk());

        verify(service).search("campbell", Set.of("AWARD", "PROPOSAL", "SUBAWARD"));
    }

    @Test
    void globalSearchWithoutRecordTypesSearchesEverything() throws Exception {
        GlobalSearchService service = mock(GlobalSearchService.class);
        when(service.search(anyString(), any())).thenReturn(
                new GlobalSearchResponse("campbell", 0, List.of(), List.of()));

        mvc(new GlobalSearchController(service)).perform(get("/api/global-search")
                        .param("query", "campbell"))
                .andExpect(status().isOk());

        verify(service).search("campbell", Set.of());
    }
}
