package edu.bu.archive.adapter.in.web;

import edu.bu.archive.adapter.in.web.dto.protocol.ProtocolActionResponse;
import edu.bu.archive.adapter.in.web.dto.protocol.ProtocolFundingResponse;
import edu.bu.archive.adapter.in.web.dto.protocol.ProtocolLocationResponse;
import edu.bu.archive.adapter.in.web.dto.protocol.ProtocolPersonResponse;
import edu.bu.archive.adapter.in.web.dto.protocol.ProtocolResearchAreaResponse;
import edu.bu.archive.adapter.in.web.dto.protocol.ProtocolSubmissionResponse;
import edu.bu.archive.adapter.in.web.dto.protocol.ProtocolVersionResponse;
import edu.bu.archive.application.protocol.ProtocolArchiveService;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.time.LocalDate;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ProtocolArchiveControllerTest {

    private ProtocolArchiveService service;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        service = mock(ProtocolArchiveService.class);
        mockMvc = MockMvcBuilders.standaloneSetup(
                new ProtocolArchiveController(service)
        ).build();
    }

    @Test
    void historyPreservesPhysicalProtocolIds() throws Exception {
        when(service.findHistory("000100"))
                .thenReturn(List.of(version(100L, 2)));

        mockMvc.perform(get("/api/protocols/000100/history"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].protocolId").value(100))
                .andExpect(jsonPath("$[0].sequenceNumber").value(2));
    }

    @Test
    void personnelIsScopedToExactProtocolId() throws Exception {
        when(service.findPersonnel(100L)).thenReturn(
                List.of(
                        new ProtocolPersonResponse(
                                10L,
                                100L,
                                999L,
                                "000100",
                                2,
                                "P1",
                                "Researcher",
                                "PI",
                                null,
                                "1",
                                null,
                                null,
                                null,
                                null,
                                null,
                                List.of()
                        )
                )
        );

        mockMvc.perform(
                        get(
                                "/api/protocols/versions/100/personnel"
                        )
                )
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].protocolPersonId").value(10))
                .andExpect(
                        jsonPath("$[0].protocolId").value(100)
                )
                .andExpect(
                        jsonPath("$[0].sourceProtocolId").value(999)
                )
                .andExpect(jsonPath("$[0].units").isArray());

        verify(service).findPersonnel(100L);
    }

    @Test
    void fundingIsScopedToExactProtocolId() throws Exception {
        when(service.findFunding(100L)).thenReturn(
                List.of(
                        new ProtocolFundingResponse(
                                20L,
                                100L,
                                999L,
                                "000100",
                                2,
                                "1",
                                "00001234",
                                "Sponsor",
                                null,
                                null,
                                null,
                                null
                        )
                )
        );

        mockMvc.perform(
                        get("/api/protocols/versions/100/funding")
                )
                .andExpect(status().isOk())
                .andExpect(
                        jsonPath("$[0].protocolFundingSourceId")
                                .value(20)
                )
                .andExpect(jsonPath("$[0].protocolId").value(100))
                .andExpect(
                        jsonPath("$[0].sourceProtocolId").value(999)
                )
                .andExpect(
                        jsonPath("$[0].fundingSourceNumber")
                                .value("00001234")
                );

        verify(service).findFunding(100L);
    }

    @Test
    void researchAreasAreScopedToExactProtocolId() throws Exception {
        when(service.findResearchAreas(100L)).thenReturn(
                List.of(
                        new ProtocolResearchAreaResponse(
                                30L,
                                100L,
                                999L,
                                "000100",
                                2,
                                "RA-1",
                                null,
                                null,
                                null,
                                null
                        )
                )
        );
        mockMvc.perform(
                        get(
                                "/api/protocols/versions/100/"
                                        + "research-areas"
                        )
                )
                .andExpect(status().isOk())
                .andExpect(
                        jsonPath("$[0].protocolResearchAreaId")
                                .value(30)
                )
                .andExpect(
                        jsonPath("$[0].researchAreaCode").value("RA-1")
                );
        verify(service).findResearchAreas(100L);
    }

    @Test
    void locationsAreScopedToExactProtocolId() throws Exception {
        when(service.findLocations(100L)).thenReturn(
                List.of(
                        new ProtocolLocationResponse(
                                40L, 100L, 999L, "000100", 2,
                                "NUMBER_SEQUENCE",
                                "1", "ORG", 50L,
                                null, null, null, null
                        )
                )
        );
        mockMvc.perform(
                        get("/api/protocols/versions/100/locations")
                )
                .andExpect(status().isOk())
                .andExpect(
                        jsonPath("$[0].protocolLocationId").value(40)
                )
                .andExpect(
                        jsonPath("$[0].organizationId").value("ORG")
                );
        verify(service).findLocations(100L);
    }

    @Test
    void submissionsAreScopedToExactProtocolId() throws Exception {
        when(service.findSubmissions(100L)).thenReturn(
                List.of(
                        new ProtocolSubmissionResponse(
                                50L, 100L, 100L, "000100", 2, 3,
                                "SCHEDULE", "COMMITTEE", "100", "1",
                                "200", 10L, 20L, "FULL",
                                LocalDate.of(2026, 1, 2), null, "MOTION",
                                5, 1, 0, 0, null, "Y",
                                null, null, null, null
                        )
                )
        );
        mockMvc.perform(
                        get(
                                "/api/protocols/versions/100/submissions"
                        )
                )
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].submissionId").value(50))
                .andExpect(jsonPath("$[0].protocolId").value(100))
                .andExpect(jsonPath("$[0].submissionNumber").value(3))
                .andExpect(
                        jsonPath("$[0].submissionDate[0]")
                                .value(2026)
                )
                .andExpect(
                        jsonPath("$[0].submissionDate[1]")
                                .value(1)
                )
                .andExpect(
                        jsonPath("$[0].submissionDate[2]")
                                .value(2)
                );
        verify(service).findSubmissions(100L);
    }

    @Test
    void actionsAreScopedToExactProtocolId() throws Exception {
        when(service.findActions(100L)).thenReturn(
                List.of(
                        new ProtocolActionResponse(
                                60L, 4, 100L, 999L, "000100", 2,
                                3, 50L, "100", "Comment", "1", "2",
                                "3", null, null, null, null, null, null,
                                1L, "OBJ", "200"
                        )
                )
        );
        mockMvc.perform(
                        get("/api/protocols/versions/100/actions")
                )
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].protocolActionId").value(60))
                .andExpect(jsonPath("$[0].protocolId").value(100))
                .andExpect(
                        jsonPath("$[0].sourceProtocolId").value(999)
                )
                .andExpect(jsonPath("$[0].submissionIdFk").value(50));
        verify(service).findActions(100L);
    }

    private ProtocolVersionResponse version(
            Long protocolId,
            Integer sequence
    ) {
        return new ProtocolVersionResponse(
                protocolId,
                "000100",
                sequence,
                null,
                "Y",
                "1",
                "Type",
                "2",
                "Status",
                "Title",
                null,
                null,
                null,
                null,
                null,
                null,
                null
        );
    }
}
