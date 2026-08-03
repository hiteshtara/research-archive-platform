package edu.bu.archive.adapter.in.web;

import edu.bu.archive.adapter.in.web.dto.PageResponse;
import edu.bu.archive.adapter.in.web.dto.award.TimeAndMoneyActionResponse;
import edu.bu.archive.adapter.in.web.dto.award.TimeAndMoneyDocumentResponse;
import edu.bu.archive.adapter.in.web.dto.award.TimeAndMoneyHistoryEntryResponse;
import edu.bu.archive.adapter.in.web.dto.award.TimeAndMoneySummaryResponse;
import edu.bu.archive.adapter.in.web.dto.award.TimeAndMoneyTransactionDetailResponse;
import edu.bu.archive.adapter.in.web.dto.award.TimeAndMoneyTransactionResponse;
import edu.bu.archive.application.award.AwardArchiveService;
import edu.bu.archive.application.award.AwardContactService;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.NoSuchElementException;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/*
 * HTTP-layer routing/404 tests for AwardV1Controller's Time and Money
 * endpoints - see docs/architecture/AWARD_TIME_AND_MONEY_DESIGN.md and
 * AwardV1ControllerTest's own amounts tests, which this mirrors. Kept
 * as its own file given the size of this feature area.
 */
class TimeAndMoneyControllerTest {

    private AwardArchiveService service;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        service = mock(AwardArchiveService.class);
        AwardContactService contactService = mock(AwardContactService.class);
        AwardV1Controller controller =
                new AwardV1Controller(service, contactService);
        mockMvc = MockMvcBuilders
                .standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    void summaryIsRoutedUnderTheV1Prefix() throws Exception {
        TimeAndMoneySummaryResponse summary = new TimeAndMoneySummaryResponse(
                3L, "100004-00003", 7,
                BigDecimal.TEN, BigDecimal.ONE, BigDecimal.TEN,
                BigDecimal.TEN, BigDecimal.ONE, BigDecimal.TEN,
                2L, "281518", LocalDate.of(2021, 1, 1), "Supplement"
        );
        when(service.findTimeAndMoneySummary(3L)).thenReturn(summary);

        mockMvc.perform(get("/api/v1/awards/3/time-and-money/summary"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.awardNumber").value("100004-00003"))
                .andExpect(jsonPath("$.lastTimeAndMoneyDocumentNumber")
                        .value("281518"));

        verify(service).findTimeAndMoneySummary(3L);
    }

    @Test
    void summaryPropagatesNotFoundAsAnHttp404() throws Exception {
        when(service.findTimeAndMoneySummary(999L))
                .thenThrow(new NoSuchElementException("Award not found: 999"));

        mockMvc.perform(get("/api/v1/awards/999/time-and-money/summary"))
                .andExpect(status().isNotFound());
    }

    @Test
    void actionsIsPaginatedLikeAmounts() throws Exception {
        TimeAndMoneyActionResponse action = new TimeAndMoneyActionResponse(
                1L, "100004-00003", "281518", "3", "Supplement",
                LocalDate.of(2021, 1, 1), "comments", "PROCESSED",
                LocalDateTime.of(2021, 1, 1, 0, 0),
                "jsmith", LocalDateTime.of(2021, 1, 1, 0, 0)
        );
        PageResponse<TimeAndMoneyActionResponse> page = new PageResponse<>(
                List.of(action), 0, 50, 1L, 1, true, true
        );
        when(service.findTimeAndMoneyActions(3L, 0, 50)).thenReturn(page);

        mockMvc.perform(get("/api/v1/awards/3/time-and-money/actions"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].timeAndMoneyDocumentNumber")
                        .value("281518"));

        verify(service).findTimeAndMoneyActions(3L, 0, 50);
    }

    @Test
    void actionsPropagatesNotFoundAsAnHttp404() throws Exception {
        when(service.findTimeAndMoneyActions(999L, 0, 50))
                .thenThrow(new NoSuchElementException("Award not found: 999"));

        mockMvc.perform(get("/api/v1/awards/999/time-and-money/actions"))
                .andExpect(status().isNotFound());
    }

    @Test
    void historyIsPaginatedLikeAmounts() throws Exception {
        TimeAndMoneyHistoryEntryResponse entry =
                new TimeAndMoneyHistoryEntryResponse(
                        10L, 3L, "100004-00003", 7,
                        555L, "281518", 6,
                        BigDecimal.TEN, BigDecimal.ONE, BigDecimal.TEN,
                        BigDecimal.ONE, BigDecimal.ONE,
                        BigDecimal.TEN, BigDecimal.ONE, BigDecimal.TEN,
                        LocalDate.of(2021, 1, 1), true
                );
        PageResponse<TimeAndMoneyHistoryEntryResponse> page =
                new PageResponse<>(List.of(entry), 0, 50, 1L, 1, true, true);
        when(service.findTimeAndMoneyHistory(3L, 0, 50)).thenReturn(page);

        mockMvc.perform(get("/api/v1/awards/3/time-and-money/history"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].originatingAwardVersion")
                        .value(6))
                .andExpect(jsonPath("$.content[0].sequenceNumber").value(7))
                .andExpect(jsonPath("$.content[0].timeAndMoneyCreated")
                        .value(true));

        verify(service).findTimeAndMoneyHistory(3L, 0, 50);
    }

    @Test
    void historyPropagatesNotFoundAsAnHttp404() throws Exception {
        when(service.findTimeAndMoneyHistory(999L, 0, 50))
                .thenThrow(new NoSuchElementException("Award not found: 999"));

        mockMvc.perform(get("/api/v1/awards/999/time-and-money/history"))
                .andExpect(status().isNotFound());
    }

    @Test
    void transactionIsRoutedUnderTheV1Prefix() throws Exception {
        TimeAndMoneyTransactionDetailResponse detail =
                new TimeAndMoneyTransactionDetailResponse(
                        900L, "100004-00003", 7, "281518",
                        "100004-00001", "100004-00003",
                        BigDecimal.TEN, BigDecimal.ONE, BigDecimal.TEN,
                        BigDecimal.TEN, BigDecimal.ONE, BigDecimal.TEN,
                        "comments", "PRIMARY"
                );
        TimeAndMoneyTransactionResponse transaction =
                new TimeAndMoneyTransactionResponse(
                        555L, "281518", "100004-00001", "100004-00003",
                        BigDecimal.TEN, BigDecimal.ONE, BigDecimal.TEN,
                        BigDecimal.TEN, BigDecimal.ONE, BigDecimal.TEN,
                        "comments", "Y", "07/01/2020 - 06/30/2021",
                        List.of(detail)
                );
        when(service.findTimeAndMoneyTransaction(3L, 555L))
                .thenReturn(transaction);

        mockMvc.perform(get(
                        "/api/v1/awards/3/time-and-money/transactions/555"
                ))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pendingTransactionId").value(555))
                .andExpect(jsonPath("$.details[0].transactionDetailId")
                        .value(900));

        verify(service).findTimeAndMoneyTransaction(3L, 555L);
    }

    @Test
    void transactionPropagatesNotFoundAsAnHttp404() throws Exception {
        when(service.findTimeAndMoneyTransaction(3L, 999L))
                .thenThrow(new NoSuchElementException(
                        "Time and Money transaction not found: 999"
                ));

        mockMvc.perform(get(
                        "/api/v1/awards/3/time-and-money/transactions/999"
                ))
                .andExpect(status().isNotFound());
    }

    @Test
    void documentIsRoutedUnderTheV1Prefix() throws Exception {
        TimeAndMoneyDocumentResponse document =
                new TimeAndMoneyDocumentResponse(
                        "281518", "100004-00001", "PROCESSED",
                        LocalDateTime.of(2021, 1, 1, 0, 0)
                );
        when(service.findTimeAndMoneyDocument(3L, "281518"))
                .thenReturn(document);

        mockMvc.perform(get(
                        "/api/v1/awards/3/time-and-money/documents/281518"
                ))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.timeAndMoneyDocumentNumber")
                        .value("281518"))
                .andExpect(jsonPath("$.rootAwardNumber")
                        .value("100004-00001"));

        verify(service).findTimeAndMoneyDocument(3L, "281518");
    }

    @Test
    void documentPropagatesNotFoundAsAnHttp404() throws Exception {
        when(service.findTimeAndMoneyDocument(3L, "NO-SUCH"))
                .thenThrow(new NoSuchElementException(
                        "Time and Money document not found: NO-SUCH"
                ));

        mockMvc.perform(get(
                        "/api/v1/awards/3/time-and-money/documents/NO-SUCH"
                ))
                .andExpect(status().isNotFound());
    }
}
