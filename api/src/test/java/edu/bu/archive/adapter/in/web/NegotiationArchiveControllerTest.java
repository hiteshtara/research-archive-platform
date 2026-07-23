package edu.bu.archive.adapter.in.web;

import edu.bu.archive.adapter.in.web.dto.negotiation.NegotiationPageResponse;
import edu.bu.archive.application.negotiation.NegotiationArchiveService;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class NegotiationArchiveControllerTest {

    private NegotiationArchiveService service;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        service = mock(NegotiationArchiveService.class);
        NegotiationArchiveController controller =
                new NegotiationArchiveController(service);
        mockMvc = MockMvcBuilders
                .standaloneSetup(controller)
                .setControllerAdvice(new AwardExceptionHandler())
                .build();
    }

    @Test
    void searchUsesTheRootRouteAndDelegatesPagination()
            throws Exception {
        NegotiationPageResponse page = new NegotiationPageResponse(
                List.of(),
                2,
                10,
                0,
                0,
                false,
                true
        );

        when(service.findPage("award", 2, 10))
                .thenReturn(page);

        mockMvc.perform(
                        get("/api/negotiations")
                                .param("query", "award")
                                .param("page", "2")
                                .param("size", "10")
                )
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page").value(2))
                .andExpect(jsonPath("$.size").value(10));

        verify(service).findPage("award", 2, 10);
    }

    @Test
    void notificationsReturnsAnEmptyJsonCollection()
            throws Exception {
        when(service.findNotifications(101L))
                .thenReturn(List.of());

        mockMvc.perform(
                        get("/api/negotiations/101/notifications")
                )
                .andExpect(status().isOk())
                .andExpect(content().json("[]"));

        verify(service).findNotifications(101L);
    }

    @Test
    void activitiesUsesTheNegotiationIdRoute()
            throws Exception {
        when(service.findActivities(101L))
                .thenReturn(List.of());

        mockMvc.perform(
                        get("/api/negotiations/101/activities")
                )
                .andExpect(status().isOk());

        verify(service).findActivities(101L);
    }
}
