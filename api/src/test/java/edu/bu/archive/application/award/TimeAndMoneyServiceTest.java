package edu.bu.archive.application.award;

import edu.bu.archive.adapter.in.web.dto.PageResponse;
import edu.bu.archive.adapter.in.web.dto.award.TimeAndMoneyActionResponse;
import edu.bu.archive.adapter.in.web.dto.award.TimeAndMoneyDocumentResponse;
import edu.bu.archive.adapter.in.web.dto.award.TimeAndMoneyHistoryEntryResponse;
import edu.bu.archive.adapter.in.web.dto.award.TimeAndMoneySummaryResponse;
import edu.bu.archive.adapter.in.web.dto.award.TimeAndMoneyTransactionDetailResponse;
import edu.bu.archive.adapter.in.web.dto.award.TimeAndMoneyTransactionHeaderRow;
import edu.bu.archive.adapter.in.web.dto.award.TimeAndMoneyTransactionResponse;
import edu.bu.archive.adapter.out.persistence.AwardArchiveRepository;
import edu.bu.archive.adapter.out.persistence.AwardAttachmentStorage;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/*
 * Service-layer tests for AwardArchiveService's Time and Money methods
 * - see docs/architecture/AWARD_TIME_AND_MONEY_DESIGN.md. Mirrors
 * AwardArchiveServiceCompositeSectionsTest's setup/mocking style but
 * kept as its own file given the size of this feature area.
 */
class TimeAndMoneyServiceTest {

    private AwardArchiveRepository repository;
    private AwardArchiveService service;

    @BeforeEach
    void setUp() {
        repository = mock(AwardArchiveRepository.class);
        service = new AwardArchiveService(
                repository, mock(AwardAttachmentStorage.class)
        );
        when(repository.findAwardNumberForId(3L))
                .thenReturn(Optional.of("100004-00003"));
    }

    // --- Summary ---------------------------------------------------------

    @Test
    void findTimeAndMoneySummaryReturnsTheRepositoryResult() {
        TimeAndMoneySummaryResponse summary = new TimeAndMoneySummaryResponse(
                3L, "100004-00003", 7,
                BigDecimal.TEN, BigDecimal.ONE, BigDecimal.TEN,
                BigDecimal.TEN, BigDecimal.ONE, BigDecimal.TEN,
                2L, "281518", LocalDate.of(2021, 1, 1), "Supplement"
        );
        when(repository.findTimeAndMoneySummary(3L))
                .thenReturn(Optional.of(summary));

        assertThat(service.findTimeAndMoneySummary(3L)).isEqualTo(summary);
    }

    @Test
    void findTimeAndMoneySummaryThrowsNotFoundForAMissingAwardId() {
        when(repository.findTimeAndMoneySummary(999L))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.findTimeAndMoneySummary(999L))
                .isInstanceOf(NoSuchElementException.class);
    }

    // --- Actions -----------------------------------------------------------

    @Test
    void findTimeAndMoneyActionsBuildsAPaginatedPage() {
        TimeAndMoneyActionResponse action = new TimeAndMoneyActionResponse(
                1L, "100004-00003", "281518", "3", "Supplement",
                LocalDate.of(2021, 1, 1), "comments", "PROCESSED",
                LocalDateTime.of(2021, 1, 1, 0, 0),
                "jsmith", LocalDateTime.of(2021, 1, 1, 0, 0)
        );
        when(repository.countTimeAndMoneyActions("100004-00003"))
                .thenReturn(1L);
        when(repository.findTimeAndMoneyActions("100004-00003", 50, 0))
                .thenReturn(List.of(action));

        PageResponse<TimeAndMoneyActionResponse> page =
                service.findTimeAndMoneyActions(3L, 0, 50);

        assertThat(page.content()).containsExactly(action);
        assertThat(page.totalElements()).isEqualTo(1L);
    }

    @Test
    void findTimeAndMoneyActionsThrowsNotFoundForAMissingAwardId() {
        when(repository.findAwardNumberForId(999L))
                .thenReturn(Optional.empty());

        assertThatThrownBy(
                () -> service.findTimeAndMoneyActions(999L, 0, 50)
        ).isInstanceOf(NoSuchElementException.class);
    }

    // --- History -----------------------------------------------------------

    @Test
    void findTimeAndMoneyHistoryBuildsAPaginatedPageAndReusesTheAmountCount() {
        TimeAndMoneyHistoryEntryResponse entry =
                new TimeAndMoneyHistoryEntryResponse(
                        10L, 3L, "100004-00003", 7,
                        555L, "281518", 6,
                        BigDecimal.TEN, BigDecimal.ONE, BigDecimal.TEN,
                        BigDecimal.ONE, BigDecimal.ONE,
                        BigDecimal.TEN, BigDecimal.ONE, BigDecimal.TEN,
                        LocalDate.of(2021, 1, 1), true
                );
        when(repository.countAmountHistory("100004-00003")).thenReturn(1L);
        when(repository.findTimeAndMoneyHistory("100004-00003", 50, 0))
                .thenReturn(List.of(entry));

        PageResponse<TimeAndMoneyHistoryEntryResponse> page =
                service.findTimeAndMoneyHistory(3L, 0, 50);

        assertThat(page.content()).containsExactly(entry);
        assertThat(page.totalElements()).isEqualTo(1L);
    }

    @Test
    void findTimeAndMoneyHistoryEntryDistinguishesSequenceFromOriginatingVersion() {
        TimeAndMoneyHistoryEntryResponse entry =
                new TimeAndMoneyHistoryEntryResponse(
                        10L, 3L, "100004-00003", 7,
                        555L, "281518",
                        6, // originatingAwardVersion differs from sequenceNumber (7)
                        BigDecimal.TEN, BigDecimal.ONE, BigDecimal.TEN,
                        BigDecimal.ONE, BigDecimal.ONE,
                        BigDecimal.TEN, BigDecimal.ONE, BigDecimal.TEN,
                        LocalDate.of(2021, 1, 1), true
                );

        assertThat(entry.sequenceNumber()).isNotEqualTo(
                entry.originatingAwardVersion()
        );
    }

    // --- Transaction details -------------------------------------------

    @Test
    void findTimeAndMoneyTransactionCombinesHeaderAndDetails() {
        TimeAndMoneyTransactionHeaderRow header =
                new TimeAndMoneyTransactionHeaderRow(
                        555L, "281518", "100004-00001", "100004-00003",
                        BigDecimal.TEN, BigDecimal.ONE, BigDecimal.TEN,
                        BigDecimal.TEN, BigDecimal.ONE, BigDecimal.TEN,
                        "comments", "Y", "07/01/2020 - 06/30/2021"
                );
        TimeAndMoneyTransactionDetailResponse detail =
                new TimeAndMoneyTransactionDetailResponse(
                        900L, "100004-00003", 7, "281518",
                        "100004-00001", "100004-00003",
                        BigDecimal.TEN, BigDecimal.ONE, BigDecimal.TEN,
                        BigDecimal.TEN, BigDecimal.ONE, BigDecimal.TEN,
                        "comments", "PRIMARY"
                );
        when(repository.findTimeAndMoneyTransactionHeader(555L))
                .thenReturn(Optional.of(header));
        when(repository.findTimeAndMoneyTransactionDetails(555L))
                .thenReturn(List.of(detail));

        TimeAndMoneyTransactionResponse result =
                service.findTimeAndMoneyTransaction(3L, 555L);

        assertThat(result.pendingTransactionId()).isEqualTo(555L);
        assertThat(result.timeAndMoneyDocumentNumber()).isEqualTo("281518");
        assertThat(result.sourceAwardNumber()).isEqualTo("100004-00001");
        assertThat(result.destinationAwardNumber()).isEqualTo("100004-00003");
        assertThat(result.fandaDistributionPeriod())
                .isEqualTo("07/01/2020 - 06/30/2021");
        assertThat(result.details()).containsExactly(detail);
    }

    @Test
    void findTimeAndMoneyTransactionFallsBackToDetailDocumentNumberWhenHeaderIsGone() {
        // pending_transaction can be purged/absent for an old,
        // already-processed transaction - transaction_detail (the
        // durable ledger) still resolves it, and its own
        // timeAndMoneyDocumentNumber (NOT NULL) fills the gap.
        TimeAndMoneyTransactionDetailResponse detail =
                new TimeAndMoneyTransactionDetailResponse(
                        900L, "100004-00003", 7, "281518",
                        "100004-00001", "100004-00003",
                        BigDecimal.TEN, BigDecimal.ONE, BigDecimal.TEN,
                        BigDecimal.TEN, BigDecimal.ONE, BigDecimal.TEN,
                        "comments", "PRIMARY"
                );
        when(repository.findTimeAndMoneyTransactionHeader(555L))
                .thenReturn(Optional.empty());
        when(repository.findTimeAndMoneyTransactionDetails(555L))
                .thenReturn(List.of(detail));

        TimeAndMoneyTransactionResponse result =
                service.findTimeAndMoneyTransaction(3L, 555L);

        assertThat(result.timeAndMoneyDocumentNumber()).isEqualTo("281518");
        assertThat(result.sourceAwardNumber()).isNull();
        assertThat(result.details()).containsExactly(detail);
    }

    @Test
    void findTimeAndMoneyTransactionThrowsNotFoundWhenNeitherHeaderNorDetailsExist() {
        when(repository.findTimeAndMoneyTransactionHeader(999L))
                .thenReturn(Optional.empty());
        when(repository.findTimeAndMoneyTransactionDetails(999L))
                .thenReturn(List.of());

        assertThatThrownBy(
                () -> service.findTimeAndMoneyTransaction(3L, 999L)
        ).isInstanceOf(NoSuchElementException.class);
    }

    @Test
    void findTimeAndMoneyTransactionThrowsNotFoundForAMissingAwardId() {
        when(repository.findAwardNumberForId(999L))
                .thenReturn(Optional.empty());

        assertThatThrownBy(
                () -> service.findTimeAndMoneyTransaction(999L, 555L)
        ).isInstanceOf(NoSuchElementException.class);
    }

    // --- Document (Workflow Details) ------------------------------------

    @Test
    void findTimeAndMoneyDocumentReturnsTheRepositoryResult() {
        TimeAndMoneyDocumentResponse document =
                new TimeAndMoneyDocumentResponse(
                        "281518", "100004-00001", "PROCESSED",
                        LocalDateTime.of(2021, 1, 1, 0, 0)
                );
        when(repository.findTimeAndMoneyDocument("281518"))
                .thenReturn(Optional.of(document));

        assertThat(service.findTimeAndMoneyDocument(3L, "281518"))
                .isEqualTo(document);
    }

    @Test
    void findTimeAndMoneyDocumentThrowsNotFoundForAMissingDocumentNumber() {
        when(repository.findTimeAndMoneyDocument("NO-SUCH"))
                .thenReturn(Optional.empty());

        assertThatThrownBy(
                () -> service.findTimeAndMoneyDocument(3L, "NO-SUCH")
        ).isInstanceOf(NoSuchElementException.class);
    }

    @Test
    void findTimeAndMoneyDocumentThrowsNotFoundForAMissingAwardId() {
        when(repository.findAwardNumberForId(999L))
                .thenReturn(Optional.empty());

        assertThatThrownBy(
                () -> service.findTimeAndMoneyDocument(999L, "281518")
        ).isInstanceOf(NoSuchElementException.class);
    }
}
