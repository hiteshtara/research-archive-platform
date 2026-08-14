package edu.bu.archive.adapter.in.web;

import edu.bu.archive.adapter.in.web.dto.PageResponse;
import edu.bu.archive.adapter.in.web.dto.award.AwardBudgetLineItemResponse;
import edu.bu.archive.adapter.in.web.dto.award.AwardBudgetPeriodResponse;
import edu.bu.archive.adapter.in.web.dto.award.AwardBudgetPersonnelResponse;
import edu.bu.archive.adapter.in.web.dto.award.AwardBudgetSummaryResponse;
import edu.bu.archive.adapter.in.web.dto.award.AwardBudgetVersionResponse;
import edu.bu.archive.application.award.AwardArchiveService;
import edu.bu.archive.application.award.AwardContactService;
import edu.bu.archive.application.security.AttachmentAuthorizationService;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.NoSuchElementException;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/*
 * HTTP-layer routing/404 tests for AwardV1Controller's Budget endpoints
 * - see docs/kuali-business-rules/Budget.md. Mirrors
 * TimeAndMoneyControllerTest's own-file precedent.
 */
class AwardBudgetControllerTest {

    private AwardArchiveService service;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        service = mock(AwardArchiveService.class);
        AwardContactService contactService = mock(AwardContactService.class);
        AwardV1Controller controller =
                new AwardV1Controller(
                        service, contactService,
                        mock(AttachmentAuthorizationService.class)
                );
        mockMvc = MockMvcBuilders
                .standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    void budgetSummaryIsRoutedUnderTheV1Prefix() throws Exception {
        AwardBudgetSummaryResponse summary = new AwardBudgetSummaryResponse(
                3831872L, "103692-00002", 46,
                213641L, 37, "9", "Posted", "1054966",
                LocalDate.of(2024, 1, 1), LocalDate.of(2024, 12, 31),
                BigDecimal.TEN, BigDecimal.ONE, BigDecimal.valueOf(11),
                new BigDecimal("699246.57"), new BigDecimal("0.01")
        );
        when(service.findBudgetSummary(3831872L)).thenReturn(summary);

        mockMvc.perform(get("/api/v1/awards/3831872/budget/summary"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.awardNumber").value("103692-00002"))
                .andExpect(jsonPath("$.selectedBudgetVersionNumber").value(37))
                .andExpect(jsonPath("$.statusDescription").value("Posted"))
                .andExpect(jsonPath("$.awardBudgetTotalCostLimit").value(699246.57))
                .andExpect(jsonPath("$.budgetChangeTotalCostLimit").value(0.01));

        verify(service).findBudgetSummary(3831872L);
    }

    @Test
    void budgetSummaryPropagatesNotFoundAsAnHttp404() throws Exception {
        when(service.findBudgetSummary(999L))
                .thenThrow(new NoSuchElementException("Award not found: 999"));

        mockMvc.perform(get("/api/v1/awards/999/budget/summary"))
                .andExpect(status().isNotFound());
    }

    @Test
    void budgetVersionsIsRoutedAndPaginated() throws Exception {
        AwardBudgetVersionResponse version = new AwardBudgetVersionResponse(
                213642L, 38, 3831872L, 46, "1130568",
                "9", "Posted", LocalDate.of(2025, 1, 1), LocalDate.of(2025, 12, 31),
                BigDecimal.TEN, BigDecimal.ONE, BigDecimal.valueOf(11),
                new BigDecimal("699246.57"), new BigDecimal("0.01"), true
        );
        when(service.findBudgetVersions(3831872L, 0, 50)).thenReturn(
                new PageResponse<>(List.of(version), 0, 50, 1, 1, true, true)
        );

        mockMvc.perform(get("/api/v1/awards/3831872/budget/versions"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].budgetId").value(213642))
                .andExpect(jsonPath("$.content[0].owningAwardId").value(3831872))
                .andExpect(jsonPath("$.content[0].selected").value(true));

        verify(service).findBudgetVersions(3831872L, 0, 50);
    }

    @Test
    void budgetPeriodsIsRoutedUnderTheV1Prefix() throws Exception {
        AwardBudgetPeriodResponse period = new AwardBudgetPeriodResponse(
                1L, 1, LocalDate.of(2025, 1, 1), LocalDate.of(2025, 12, 31),
                BigDecimal.TEN, BigDecimal.ONE, BigDecimal.valueOf(11)
        );
        when(service.findBudgetPeriods(3831872L)).thenReturn(List.of(period));

        mockMvc.perform(get("/api/v1/awards/3831872/budget/periods"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].budgetPeriodId").value(1))
                .andExpect(jsonPath("$[0].periodNumber").value(1));

        verify(service).findBudgetPeriods(3831872L);
    }

    @Test
    void budgetLineItemsIsRoutedAndPaginated() throws Exception {
        AwardBudgetLineItemResponse lineItem = new AwardBudgetLineItemResponse(
                1L, 1L, 1, "Supplies", "1000",
                LocalDate.of(2025, 1, 1), LocalDate.of(2025, 12, 31),
                BigDecimal.TEN, BigDecimal.ZERO
        );
        when(service.findBudgetLineItems(3831872L, 0, 50)).thenReturn(
                new PageResponse<>(List.of(lineItem), 0, 50, 1, 1, true, true)
        );

        mockMvc.perform(get("/api/v1/awards/3831872/budget/line-items"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].description").value("Supplies"));

        verify(service).findBudgetLineItems(3831872L, 0, 50);
    }

    @Test
    void budgetPersonnelIsRoutedAndPaginated() throws Exception {
        AwardBudgetPersonnelResponse personnel = new AwardBudgetPersonnelResponse(
                1L, "P123", "Jane Doe", "1234", "Faculty",
                BigDecimal.valueOf(50000), BigDecimal.valueOf(52000)
        );
        when(service.findBudgetPersonnel(3831872L, 0, 50)).thenReturn(
                new PageResponse<>(List.of(personnel), 0, 50, 1, 1, true, true)
        );

        mockMvc.perform(get("/api/v1/awards/3831872/budget/personnel"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].fullName").value("Jane Doe"));

        verify(service).findBudgetPersonnel(3831872L, 0, 50);
    }
}
