package edu.bu.archive.adapter.in.web;

import edu.bu.archive.adapter.in.web.dto.protocol.ProtocolFundingResponse;
import edu.bu.archive.adapter.in.web.dto.protocol.ProtocolPersonResponse;
import edu.bu.archive.adapter.in.web.dto.protocol.ProtocolVersionResponse;
import edu.bu.archive.application.protocol.ProtocolArchiveService;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

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
