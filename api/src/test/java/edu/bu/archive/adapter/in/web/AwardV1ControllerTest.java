package edu.bu.archive.adapter.in.web;

import edu.bu.archive.adapter.in.web.dto.PageResponse;
import edu.bu.archive.adapter.in.web.dto.award.AwardHierarchyNodeResponse;
import edu.bu.archive.adapter.in.web.dto.award.AwardHierarchyResponse;
import edu.bu.archive.adapter.in.web.dto.award.AwardSearchResultResponse;
import edu.bu.archive.adapter.in.web.dto.award.AwardSummaryResponse;
import edu.bu.archive.adapter.in.web.dto.award.AwardVersionSummaryResponse;
import edu.bu.archive.application.award.AwardArchiveService;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.math.BigDecimal;
import java.util.List;
import java.util.NoSuchElementException;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AwardV1ControllerTest {

    private AwardArchiveService service;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        service = mock(AwardArchiveService.class);
        AwardV1Controller controller = new AwardV1Controller(service);
        mockMvc = MockMvcBuilders
                .standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    void searchIsRoutedUnderTheV1Prefix() throws Exception {
        AwardSearchResultResponse result = new AwardSearchResultResponse(
                3L, "100004-00003", 1, "Title", "Approved Award",
                "MICHAEL MCCLEAN", "Brown University",
                "SPH ENVIRONMENTAL HEALTH", BigDecimal.TEN, null, null
        );
        PageResponse<AwardSearchResultResponse> page = new PageResponse<>(
                List.of(result), 1, 10, 1L, 1, false, true
        );
        when(service.search("cancer", 1, 10)).thenReturn(page);

        mockMvc.perform(
                        get("/api/v1/awards/search")
                                .param("q", "cancer")
                                .param("page", "1")
                                .param("size", "10")
                )
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page").value(1))
                .andExpect(jsonPath("$.size").value(10))
                .andExpect(
                        jsonPath("$.content[0].awardNumber")
                                .value("100004-00003")
                );

        verify(service).search("cancer", 1, 10);
    }

    @Test
    void searchDefaultsPageAndSizeWhenOmitted() throws Exception {
        when(service.search(null, 0, 25)).thenReturn(
                new PageResponse<>(List.of(), 0, 25, 0L, 0, true, true)
        );

        mockMvc.perform(get("/api/v1/awards/search"))
                .andExpect(status().isOk());

        verify(service).search(null, 0, 25);
    }

    @Test
    void hierarchyIsRoutedUnderTheV1Prefix() throws Exception {
        AwardHierarchyNodeResponse root = new AwardHierarchyNodeResponse(
                "100004-00001", 1L, 9, null, true, "Title", "Closed",
                "MICHAEL MCCLEAN", "Brown University",
                "SPH ENVIRONMENTAL HEALTH", BigDecimal.TEN, List.of()
        );
        AwardHierarchyResponse hierarchy = new AwardHierarchyResponse(
                "100004-00001", "100004-00001", root,
                List.of("100004-00001")
        );
        when(service.findHierarchy("100004-00001"))
                .thenReturn(hierarchy);

        mockMvc.perform(get("/api/v1/awards/100004-00001/hierarchy"))
                .andExpect(status().isOk())
                .andExpect(
                        jsonPath("$.rootAwardNumber")
                                .value("100004-00001")
                );

        verify(service).findHierarchy("100004-00001");
    }

    @Test
    void hierarchyPropagatesNotFoundWithConsistentErrorShape() throws Exception {
        when(service.findHierarchy("NO-SUCH-AWARD"))
                .thenThrow(new NoSuchElementException(
                        "Award not found: NO-SUCH-AWARD"
                ));

        mockMvc.perform(get("/api/v1/awards/NO-SUCH-AWARD/hierarchy"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"))
                .andExpect(
                        jsonPath("$.message")
                                .value("Award not found: NO-SUCH-AWARD")
                )
                .andExpect(
                        jsonPath("$.path")
                                .value("/api/v1/awards/NO-SUCH-AWARD/hierarchy")
                )
                .andExpect(jsonPath("$.correlationId").exists());
    }

    @Test
    void summaryIsRoutedUnderTheV1Prefix() throws Exception {
        AwardSummaryResponse summary = new AwardSummaryResponse(
                3L, "100004-00003", 1, "Title", "Approved Award",
                "Brown University", null, "MICHAEL MCCLEAN",
                "SPH ENVIRONMENTAL HEALTH", null, null, null, null,
                BigDecimal.TEN, BigDecimal.TEN, "1", "Cost reimbursement",
                "28", "Invoice", null, null
        );
        when(service.findSummary(3L)).thenReturn(summary);

        mockMvc.perform(get("/api/v1/awards/3/summary"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.awardNumber")
                        .value("100004-00003"));

        verify(service).findSummary(3L);
    }

    @Test
    void summaryPropagatesNotFoundAsAnHttp404() throws Exception {
        when(service.findSummary(999L))
                .thenThrow(new NoSuchElementException(
                        "Award not found: 999"
                ));

        mockMvc.perform(get("/api/v1/awards/999/summary"))
                .andExpect(status().isNotFound());
    }

    @Test
    void versionsIsPaginatedLikeSearch() throws Exception {
        AwardVersionSummaryResponse version =
                new AwardVersionSummaryResponse(
                        3L, "100004-00003", 1, "Approved Award",
                        "12", "Converted Record", null, null, null
                );
        PageResponse<AwardVersionSummaryResponse> page = new PageResponse<>(
                List.of(version), 0, 50, 1L, 1, true, true
        );
        when(service.findVersions(3L, 0, 50)).thenReturn(page);

        mockMvc.perform(get("/api/v1/awards/3/versions"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.size").value(50))
                .andExpect(jsonPath("$.content[0].awardNumber")
                        .value("100004-00003"))
                .andExpect(jsonPath("$.content[0].sequenceNumber").value(1));

        verify(service).findVersions(3L, 0, 50);
    }

    @Test
    void versionsPropagatesNotFoundAsAnHttp404() throws Exception {
        when(service.findVersions(999L, 0, 50))
                .thenThrow(new NoSuchElementException(
                        "Award not found: 999"
                ));

        mockMvc.perform(get("/api/v1/awards/999/versions"))
                .andExpect(status().isNotFound());
    }
}
