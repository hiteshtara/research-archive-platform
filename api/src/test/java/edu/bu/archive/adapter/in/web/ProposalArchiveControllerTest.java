package edu.bu.archive.adapter.in.web;

import edu.bu.archive.adapter.in.web.dto.proposal.ProposalFamilySummaryResponse;
import edu.bu.archive.adapter.in.web.dto.proposal.ProposalRowResponse;
import edu.bu.archive.adapter.in.web.dto.PageResponse;
import edu.bu.archive.adapter.out.persistence.ProposalArchiveRepository;
import edu.bu.archive.application.proposal.ProposalArchiveService;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ProposalArchiveControllerTest {

    private ProposalArchiveService service;
    private ProposalArchiveRepository repository;
    private ProposalArchiveController controller;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        service = mock(ProposalArchiveService.class);
        repository = mock(ProposalArchiveRepository.class);
        controller = new ProposalArchiveController(
                service,
                repository
        );
        mockMvc = MockMvcBuilders
                .standaloneSetup(controller)
                .build();
    }

    @Test
    void familiesUsesTheLiteralRouteInsteadOfProposalNumber()
            throws Exception {
        when(repository.findFamilies(null, 50))
                .thenReturn(List.of());

        mockMvc.perform(get("/api/proposals/families"))
                .andExpect(status().isOk());

        verify(repository).findFamilies(null, 50);
        verify(service, never()).findWorkspace("families");
    }

    /*
     * QA TC-033: /api/proposals/search is the paginated Proposals search.
     * The literal route must win over {proposalNumber}, so "search" is never
     * looked up as a Proposal number - which is also why the UI's workspace
     * client treats "search" as not found instead of calling this URL.
     */
    @Test
    void searchUsesTheLiteralRouteInsteadOfProposalNumber()
            throws Exception {
        mockMvc.perform(get("/api/proposals/search")
                        .param("sponsor", "nih")
                        .param("page", "1")
                        .param("size", "25"))
                .andExpect(status().isOk());

        verify(service).findFamilyPage(
                org.mockito.ArgumentMatchers.isNull(),
                org.mockito.ArgumentMatchers.argThat(
                        filters -> "nih".equals(filters.sponsor())),
                org.mockito.ArgumentMatchers.eq(1),
                org.mockito.ArgumentMatchers.eq(25)
        );
        verify(service, never()).findWorkspace("search");
    }

    @Test
    void workspaceStillResolvesARealProposalNumber() throws Exception {
        mockMvc.perform(get("/api/proposals/01091939"))
                .andExpect(status().isOk());

        verify(service).findWorkspace("01091939");
        verify(service, never()).findFamilyPage(
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyInt(),
                org.mockito.ArgumentMatchers.anyInt()
        );
    }

    @Test
    void familiesClampsTheLimitToTheAwardBounds() {
        ProposalFamilySummaryResponse family =
                new ProposalFamilySummaryResponse(
                        "P-100",
                        "Proposal title",
                        "ACTIVE",
                        "Sponsor",
                        "Unit",
                        "Principal Investigator",
                        3,
                        10L
                );

        when(repository.findFamilies("cancer", 200))
                .thenReturn(List.of(family));

        List<ProposalFamilySummaryResponse> result =
                controller.families("cancer", 500);

        assertThat(result).containsExactly(family);
        verify(repository).findFamilies("cancer", 200);
    }

    @Test
    void historyDelegatesPaginationToTheService() {
        PageResponse<ProposalRowResponse> page =
                new PageResponse<>(
                        List.of(),
                        2,
                        10,
                        0,
                        0,
                        false,
                        true
                );

        when(service.findVersionPage(
                "P-100",
                2,
                10
        )).thenReturn(page);

        PageResponse<ProposalRowResponse> result =
                controller.history(
                        "P-100",
                        2,
                        10
                ).getBody();

        assertThat(result).isEqualTo(page);
        verify(service).findVersionPage(
                "P-100",
                2,
                10
        );
    }
}
