package edu.bu.archive.adapter.in.web;

import edu.bu.archive.adapter.in.web.dto.subaward.SubawardPageResponse;
import edu.bu.archive.application.subaward.SubawardArchiveService;

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

class SubawardArchiveControllerTest {

    private SubawardArchiveService service;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        service = mock(SubawardArchiveService.class);
        SubawardArchiveController controller =
                new SubawardArchiveController(service);
        mockMvc = MockMvcBuilders
                .standaloneSetup(controller)
                .setControllerAdvice(new AwardExceptionHandler())
                .build();
    }

    @Test
    void searchUsesTheRootRouteAndDelegatesPagination()
            throws Exception {
        SubawardPageResponse page = new SubawardPageResponse(
                List.of(), 2, 10, 0, 0, false, true
        );
        when(service.findPage("1004", 2, 10)).thenReturn(page);

        mockMvc.perform(
                        get("/api/subawards")
                                .param("query", "1004")
                                .param("page", "2")
                                .param("size", "10")
                )
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page").value(2))
                .andExpect(jsonPath("$.size").value(10));

        verify(service).findPage("1004", 2, 10);
    }

    @Test
    void notificationsReturnsAnEmptyJsonCollection()
            throws Exception {
        when(service.findNotifications(101L)).thenReturn(List.of());

        mockMvc.perform(get("/api/subawards/101/notifications"))
                .andExpect(status().isOk())
                .andExpect(content().json("[]"));

        verify(service).findNotifications(101L);
    }

    @Test
    void closeoutUsesTheSubawardIdRouteAndReturnsAnEmptyCollection()
            throws Exception {
        when(service.findCloseout(101L)).thenReturn(List.of());

        mockMvc.perform(get("/api/subawards/101/closeout"))
                .andExpect(status().isOk())
                .andExpect(content().json("[]"));

        verify(service).findCloseout(101L);
    }
}
