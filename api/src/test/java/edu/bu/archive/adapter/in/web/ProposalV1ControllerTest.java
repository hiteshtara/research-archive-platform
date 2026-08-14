package edu.bu.archive.adapter.in.web;

import edu.bu.archive.adapter.in.web.dto.PageResponse;
import edu.bu.archive.adapter.in.web.dto.proposal.ProposalAssociatedUnitResponse;
import edu.bu.archive.adapter.in.web.dto.proposal.ProposalAttachmentsResponse;
import edu.bu.archive.adapter.in.web.dto.proposal.ProposalCommentsResponse;
import edu.bu.archive.adapter.in.web.dto.proposal.ProposalCustomDataResponse;
import edu.bu.archive.adapter.in.web.dto.proposal.ProposalFundedAwardResponse;
import edu.bu.archive.adapter.in.web.dto.proposal.ProposalPersonResponse;
import edu.bu.archive.adapter.in.web.dto.proposal.ProposalSummaryResponse;
import edu.bu.archive.adapter.in.web.dto.proposal.ProposalUnitsResponse;
import edu.bu.archive.adapter.in.web.dto.proposal.ProposalVersionSummaryResponse;
import edu.bu.archive.application.proposal.ProposalArchiveV1Service;
import edu.bu.archive.application.security.AttachmentAuthorizationService;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.NoSuchElementException;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ProposalV1ControllerTest {

    private ProposalArchiveV1Service service;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        service = mock(ProposalArchiveV1Service.class);
        ProposalV1Controller controller = new ProposalV1Controller(
                service, mock(AttachmentAuthorizationService.class)
        );
        mockMvc = MockMvcBuilders
                .standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    void summaryIsRoutedUnderTheV1Prefix() throws Exception {
        ProposalSummaryResponse summary = new ProposalSummaryResponse(
                1238613L, "01157400", 7, "125761", "Title", "Funded",
                "ACTIVE", "Type", "Activity", "1262160000", "Lead Unit",
                "S1", "Sponsor", "U1", "PI", null, null, null, null, null,
                null, null, null, null, null
        );
        when(service.findSummary(1238613L)).thenReturn(summary);

        mockMvc.perform(get("/api/v1/proposals/1238613"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.proposalNumber").value("01157400"))
                .andExpect(jsonPath("$.workflowDocumentNumber").value("125761"));

        verify(service).findSummary(1238613L);
    }

    @Test
    void summaryPropagatesNotFoundWithConsistentErrorShape() throws Exception {
        when(service.findSummary(999L))
                .thenThrow(new NoSuchElementException("Proposal not found: 999"));

        mockMvc.perform(get("/api/v1/proposals/999"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test
    void versionsIsRoutedUnderTheV1Prefix() throws Exception {
        ProposalVersionSummaryResponse version = new ProposalVersionSummaryResponse(
                2986L, "205", 2, "125761", "ACTIVE", "Funded", "Title", null
        );
        PageResponse<ProposalVersionSummaryResponse> page =
                new PageResponse<>(List.of(version), 0, 50, 1L, 1, true, true);
        when(service.findVersions(2986L, 0, 50)).thenReturn(page);

        mockMvc.perform(get("/api/v1/proposals/2986/versions"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].proposalNumber").value("205"));

        verify(service).findVersions(2986L, 0, 50);
    }

    @Test
    void peopleIsRoutedUnderTheV1Prefix() throws Exception {
        ProposalPersonResponse person = new ProposalPersonResponse(
                126591L, "U56572816", "LOIS K HORWITZ", "PI", null, true,
                "N", null, null, null, null
        );
        when(service.findPeople(212L)).thenReturn(List.of(person));

        mockMvc.perform(get("/api/v1/proposals/212/people"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].fullName").value("LOIS K HORWITZ"))
                .andExpect(jsonPath("$[0].principalInvestigator").value(true));

        verify(service).findPeople(212L);
    }

    @Test
    void unitsIsRoutedUnderTheV1PrefixAndKeepsBothListsDistinct()
            throws Exception {
        ProposalAssociatedUnitResponse associatedUnit =
                new ProposalAssociatedUnitResponse(
                        126592L, 126591L, "LOIS K HORWITZ", "1262160000",
                        "MET ACTUARIAL SCIENCE", true
                );
        ProposalUnitsResponse units = new ProposalUnitsResponse(
                List.of(associatedUnit), List.of()
        );
        when(service.findUnits(212L)).thenReturn(units);

        mockMvc.perform(get("/api/v1/proposals/212/units"))
                .andExpect(status().isOk())
                .andExpect(
                        jsonPath("$.associatedUnits[0].unitNumber")
                                .value("1262160000")
                )
                .andExpect(jsonPath("$.unitContacts").isEmpty());

        verify(service).findUnits(212L);
    }

    @Test
    void attachmentsIsRoutedUnderTheV1Prefix() throws Exception {
        when(service.findAttachments(1238613L))
                .thenReturn(new ProposalAttachmentsResponse(List.of()));

        mockMvc.perform(get("/api/v1/proposals/1238613/attachments"))
                .andExpect(status().isOk());

        verify(service).findAttachments(1238613L);
    }

    @Test
    void commentsIsRoutedUnderTheV1Prefix() throws Exception {
        when(service.findComments(2986L))
                .thenReturn(new ProposalCommentsResponse(List.of()));

        mockMvc.perform(get("/api/v1/proposals/2986/comments"))
                .andExpect(status().isOk());

        verify(service).findComments(2986L);
    }

    @Test
    void fundedAwardsIsRoutedUnderTheV1PrefixAndCarriesTheNavigableAwardId()
            throws Exception {
        // The database relationship's exact historical award_id
        // (148155, a long-superseded version) is preserved for audit,
        // but navigableCurrentAwardId (605555) is what a client
        // navigates to - resolved server-side, never guessed by the UI.
        ProposalFundedAwardResponse fundedAward =
                new ProposalFundedAwardResponse(
                        "200268-00001", "Title", "Closed", 2, 1, 5, true,
                        148155L, 605555L, 148183L
                );
        when(service.findFundedAwards(2986L))
                .thenReturn(List.of(fundedAward));

        mockMvc.perform(get("/api/v1/proposals/2986/funded-awards"))
                .andExpect(status().isOk())
                .andExpect(
                        jsonPath("$[0].awardNumber").value("200268-00001")
                )
                .andExpect(
                        jsonPath("$[0].navigableCurrentAwardId").value(605555)
                )
                .andExpect(jsonPath("$[0].relationshipActive").value(true));

        verify(service).findFundedAwards(2986L);
    }

    @Test
    void customDataIsRoutedUnderTheV1PrefixAndCarriesTheResolvedLabel()
            throws Exception {
        ProposalCustomDataResponse row = new ProposalCustomDataResponse(
                477845L, 480L, "Submitted Date", "ip_submission_date",
                "Date", null, "08/09/2011", null, "dhaywood"
        );
        when(service.findCustomData(2986L)).thenReturn(List.of(row));

        mockMvc.perform(get("/api/v1/proposals/2986/custom-data"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].label").value("Submitted Date"))
                .andExpect(jsonPath("$[0].value").value("08/09/2011"))
                .andExpect(jsonPath("$[0].customAttributeId").value(480));

        verify(service).findCustomData(2986L);
    }

    @Test
    void customDataReturns404WhenTheProposalDoesNotExist() throws Exception {
        when(service.findCustomData(999L))
                .thenThrow(new NoSuchElementException("Proposal not found: 999"));

        mockMvc.perform(get("/api/v1/proposals/999/custom-data"))
                .andExpect(status().isNotFound());
    }
}
