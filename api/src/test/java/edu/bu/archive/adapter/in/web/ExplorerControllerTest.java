package edu.bu.archive.adapter.in.web;

import edu.bu.archive.adapter.in.web.dto.award.AwardAttachmentResponse;
import edu.bu.archive.adapter.in.web.dto.award.AwardDocumentNumberMatchResponse;
import edu.bu.archive.adapter.in.web.dto.explorer.ExplorerAwardContactsResponse;
import edu.bu.archive.adapter.in.web.dto.explorer.ExplorerAwardResponse;
import edu.bu.archive.adapter.in.web.dto.explorer.ExplorerPersonResponse;
import edu.bu.archive.adapter.in.web.dto.explorer.ExplorerProposalDiscoveryResponse;
import edu.bu.archive.adapter.in.web.dto.explorer.ExplorerRolodexResponse;
import edu.bu.archive.adapter.in.web.dto.explorer.ExplorerUnitAdministratorResponse;
import edu.bu.archive.adapter.in.web.dto.explorer.ExplorerUnitResponse;
import edu.bu.archive.adapter.in.web.dto.award.AwardCentralAdministrationContactResponse;
import edu.bu.archive.adapter.in.web.dto.award.AwardSponsorContactResponse;
import edu.bu.archive.adapter.in.web.dto.award.AwardUnitContactResponse;
import edu.bu.archive.adapter.in.web.dto.award.AwardUnitDetailsResponse;
import edu.bu.archive.application.award.ExplorerService;

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

class ExplorerControllerTest {

    private ExplorerService service;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        service = mock(ExplorerService.class);
        ExplorerController controller = new ExplorerController(service);
        mockMvc = MockMvcBuilders
                .standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    void awardIsRoutedUnderTheExplorerPrefix() throws Exception {
        ExplorerAwardResponse award = new ExplorerAwardResponse(
                985585L, "100012-00002", 7, "Title", "Closed",
                "JOHN T CLARKE", "300940", null, "1203250000",
                "CAS SPACE PHYSICS", true
        );
        when(service.findAward("100012-00002")).thenReturn(award);

        mockMvc.perform(
                        get("/api/v1/explorer/awards")
                                .param("awardNumber", "100012-00002")
                )
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.awardId").value(985585))
                .andExpect(jsonPath("$.principalInvestigator")
                        .value("JOHN T CLARKE"));

        verify(service).findAward("100012-00002");
    }

    @Test
    void awardPropagatesNotFoundAsAnHttp404() throws Exception {
        when(service.findAward("NO-SUCH"))
                .thenThrow(new NoSuchElementException("not found"));

        mockMvc.perform(get("/api/v1/explorer/awards").param("awardNumber", "NO-SUCH"))
                .andExpect(status().isNotFound());
    }

    // @NotBlank/@Positive @RequestParam rejection (400) is proven in
    // ExplorerControllerValidationTest instead: @Validated method-parameter
    // constraints are enforced by a Spring-managed AOP proxy
    // (MethodValidationPostProcessor, autoconfigured by Spring Boot), which
    // a standalone MockMvcBuilders controller instance never receives -
    // confirmed empirically (these two cases returned 200, not 400, under
    // standaloneSetup). AwardV1ControllerTest has the same
    // standaloneSetup shape and, consistent with this, does not attempt to
    // test its own @Min/@Max @RequestParam rejections either.

    @Test
    void workflowIsRoutedUnderTheExplorerPrefix() throws Exception {
        AwardDocumentNumberMatchResponse match =
                new AwardDocumentNumberMatchResponse(
                        1135067L, "100567-00001", 6, "328797", "Award",
                        "Title", "Closed"
                );
        when(service.findWorkflow("328797")).thenReturn(match);

        mockMvc.perform(
                        get("/api/v1/explorer/workflows")
                                .param("documentNumber", "328797")
                )
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sequenceNumber").value(6));

        verify(service).findWorkflow("328797");
    }

    @Test
    void unitIsRoutedUnderTheExplorerPrefix() throws Exception {
        ExplorerUnitAdministratorResponse anthony =
                new ExplorerUnitAdministratorResponse(
                        "U98756203", "ANTHONY J MOY", "3",
                        "OSP Administrator", "C", "TMOY@BU.EDU",
                        "617-353-4365"
                );
        ExplorerUnitResponse unit = new ExplorerUnitResponse(
                "1203250000", "CAS SPACE PHYSICS", "1200000000",
                "COLLEGE OF ARTS & SCIENCES (CAS)", "1", List.of(anthony)
        );
        when(service.findUnit("1203250000")).thenReturn(unit);

        mockMvc.perform(
                        get("/api/v1/explorer/units").param("unitNumber", "1203250000")
                )
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.unitName").value("CAS SPACE PHYSICS"))
                .andExpect(
                        jsonPath("$.administrators[0].defaultGroupFlag")
                                .value("C")
                );
    }

    @Test
    void unitAdministratorsIsRoutedUnderTheExplorerPrefix() throws Exception {
        when(service.findUnitAdministrators("1203250000")).thenReturn(List.of());

        mockMvc.perform(
                        get("/api/v1/explorer/unit-administrators")
                                .param("unitNumber", "1203250000")
                )
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray());

        verify(service).findUnitAdministrators("1203250000");
    }

    @Test
    void awardContactsAggregatesAllFourSections() throws Exception {
        ExplorerAwardResponse award = new ExplorerAwardResponse(
                985585L, "100012-00002", 7, "Title", "Closed",
                "JOHN T CLARKE", "300940", null, "1203250000",
                "CAS SPACE PHYSICS", true
        );
        AwardUnitDetailsResponse unitDetails = new AwardUnitDetailsResponse(
                "1203250000", "CAS SPACE PHYSICS", "1200000000",
                "COLLEGE OF ARTS & SCIENCES (CAS)", "1", true
        );
        AwardCentralAdministrationContactResponse centralAdmin =
                new AwardCentralAdministrationContactResponse(
                        "U98756203", "ANTHONY J MOY", "OSP Administrator",
                        "TMOY@BU.EDU", "617-353-4365"
                );
        ExplorerAwardContactsResponse aggregate =
                new ExplorerAwardContactsResponse(
                        award, List.of(), unitDetails,
                        List.<AwardUnitContactResponse>of(),
                        List.<AwardSponsorContactResponse>of(),
                        List.of(centralAdmin)
                );
        when(service.findAwardContacts(985585L)).thenReturn(aggregate);

        mockMvc.perform(
                        get("/api/v1/explorer/award-contacts")
                                .param("awardId", "985585")
                )
                .andExpect(status().isOk())
                .andExpect(
                        jsonPath("$.centralAdministrationContacts[0].fullName")
                                .value("ANTHONY J MOY")
                );
    }

    @Test
    void personIsRoutedUnderTheExplorerPrefix() throws Exception {
        ExplorerPersonResponse person = new ExplorerPersonResponse(
                "U44984650", "NANCY", null, "SCHINDELE", "NANCY SCHINDELE",
                "NANCYSCH@BU.EDU", "617-358-5117"
        );
        when(service.findPerson("U44984650")).thenReturn(person);

        mockMvc.perform(
                        get("/api/v1/explorer/persons").param("personId", "U44984650")
                )
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fullName").value("NANCY SCHINDELE"));
    }

    @Test
    void rolodexIsRoutedUnderTheExplorerPrefix() throws Exception {
        ExplorerRolodexResponse rolodex = new ExplorerRolodexResponse(
                501L, "Jane", "Smith", "NIH", "301-555-0100",
                "jane.smith@nih.gov", "Bethesda", "MD", true
        );
        when(service.findRolodex(501L)).thenReturn(rolodex);

        mockMvc.perform(
                        get("/api/v1/explorer/rolodex").param("rolodexId", "501")
                )
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.organization").value("NIH"));
    }

    @Test
    void sponsorsIsRoutedUnderTheExplorerPrefixBySponsorCode() throws Exception {
        ExplorerAwardResponse award = new ExplorerAwardResponse(
                985585L, "100012-00002", 7, "Title", "Closed",
                "JOHN T CLARKE", "300940", null, "1203250000",
                "CAS SPACE PHYSICS", true
        );
        when(service.findSponsorsByCode("NIH")).thenReturn(List.of(award));

        mockMvc.perform(
                        get("/api/v1/explorer/sponsors").param("sponsorCode", "NIH")
                )
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].awardNumber").value("100012-00002"));

        verify(service).findSponsorsByCode("NIH");
    }

    @Test
    void attachmentsIsRoutedUnderTheExplorerPrefix() throws Exception {
        AwardAttachmentResponse attachment = new AwardAttachmentResponse(
                1L, "100068-00001", 1, "agreement.pdf", "application/pdf",
                null, null, null, 1024L, "UPLOADED", true, null
        );
        when(service.findAttachments(1833767L)).thenReturn(List.of(attachment));

        mockMvc.perform(
                        get("/api/v1/explorer/attachments").param("awardId", "1833767")
                )
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].fileName").value("agreement.pdf"));
    }

    @Test
    void proposalsIsRoutedUnderTheExplorerPrefixWithAllFilters() throws Exception {
        ExplorerProposalDiscoveryResponse row = new ExplorerProposalDiscoveryResponse(
                1238613L, "01157400", "Title", "125761",
                "National Science Foundation", "Jane Q Investigator", 3,
                "200268-00001", 605555L, "Award Title",
                new java.math.BigDecimal("1500000.00"),
                new java.math.BigDecimal("1200000.00"),
                148155L
        );
        when(service.findProposalDiscovery(
                true, true, new java.math.BigDecimal("1000000"),
                "301573", "National Science", "1203250000", "Funded",
                "Investigator", "New", "Research",
                java.time.LocalDate.of(2020, 1, 1),
                java.time.LocalDate.of(2025, 12, 31),
                0, 50
        )).thenReturn(List.of(row));

        mockMvc.perform(
                        get("/api/v1/explorer/proposals")
                                .param("hasAttachments", "true")
                                .param("hasFundedAward", "true")
                                .param("minimumAwardAmount", "1000000")
                                .param("sponsorCode", "301573")
                                .param("sponsorName", "National Science")
                                .param("leadUnitNumber", "1203250000")
                                .param("proposalStatus", "Funded")
                                .param("personName", "Investigator")
                                .param("proposalType", "New")
                                .param("activityType", "Research")
                                .param("dateFrom", "2020-01-01")
                                .param("dateTo", "2025-12-31")
                                .param("page", "0")
                                .param("size", "50")
                )
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].proposalNumber").value("01157400"))
                .andExpect(jsonPath("$[0].principalInvestigatorName").value("Jane Q Investigator"))
                .andExpect(jsonPath("$[0].sponsorName").value("National Science Foundation"))
                .andExpect(jsonPath("$[0].navigableCurrentAwardId").value(605555))
                .andExpect(jsonPath("$[0].exactLinkedAwardId").value(148155))
                .andExpect(jsonPath("$[0].obligatedAmount").value(1500000.00));

        verify(service).findProposalDiscovery(
                true, true, new java.math.BigDecimal("1000000"),
                "301573", "National Science", "1203250000", "Funded",
                "Investigator", "New", "Research",
                java.time.LocalDate.of(2020, 1, 1),
                java.time.LocalDate.of(2025, 12, 31),
                0, 50
        );
    }

    @Test
    void proposalsAppliesDefaultsWhenNoFiltersAreGiven() throws Exception {
        when(service.findProposalDiscovery(
                null, null, null, null, null, null, null, null, null, null,
                null, null, 0, 50
        )).thenReturn(List.of());

        mockMvc.perform(get("/api/v1/explorer/proposals"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray());

        verify(service).findProposalDiscovery(
                null, null, null, null, null, null, null, null, null, null,
                null, null, 0, 50
        );
    }
}
