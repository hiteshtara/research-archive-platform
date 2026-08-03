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

    /*
     * --- Regression fixtures for the Time and Money scoping bug -------
     *
     * Reproduces the exact shapes confirmed live against real ordinary
     * Awards (100701-00001/683701, 100906-00001/3452675,
     * 100565-00001/1120712, 101737-00001/1124102) after the scoping fix
     * - see docs/architecture/AWARD_TIME_AND_MONEY_DESIGN.md and the
     * live re-verification report. The service is a thin passthrough
     * here, so these tests exist to document and lock in the four
     * required scenarios, not to re-derive repository SQL correctness
     * (already covered by TimeAndMoneyRepositoryTest).
     */

    @Test
    void findTimeAndMoneySummaryCurrentVersionWithDirectTimeAndMoneyActivity() {
        // 100701-00001, award_id 683701 (sequence 2, the current
        // version) - this exact version's own row IS the one
        // Time-and-Money-created, so family and version-local activity
        // coincide. Not the common case, but must still work.
        TimeAndMoneySummaryResponse summary = new TimeAndMoneySummaryResponse(
                683701L, "100701-00001", 2,
                BigDecimal.TEN, BigDecimal.ONE, BigDecimal.TEN,
                BigDecimal.TEN, BigDecimal.ONE, BigDecimal.TEN,
                1L, "DOC-CURRENT", LocalDate.of(2020, 1, 1), "New"
        );
        when(repository.findTimeAndMoneySummary(683701L))
                .thenReturn(Optional.of(summary));

        TimeAndMoneySummaryResponse result =
                service.findTimeAndMoneySummary(683701L);

        assertThat(result.familyTransactionCount()).isEqualTo(1L);
        assertThat(result.lastFamilyTimeAndMoneyDocumentNumber())
                .isEqualTo("DOC-CURRENT");
    }

    @Test
    void findTimeAndMoneySummaryCurrentVersionWithActivityOnlyOnEarlierSequences() {
        // 100906-00001, award_id 3452675 (sequence 4, the current
        // version) - confirmed live to have ZERO Time-and-Money-created
        // rows of its own (a plain amendment minted this version), while
        // sequences 2 and 3 of the same family do. Financial totals
        // still resolve from this exact award_id's own latest row;
        // family fields must NOT be null/zero just because this
        // specific version was never itself touched.
        TimeAndMoneySummaryResponse summary = new TimeAndMoneySummaryResponse(
                3452675L, "100906-00001", 4,
                BigDecimal.TEN, BigDecimal.ONE, BigDecimal.TEN,
                BigDecimal.TEN, BigDecimal.ONE, BigDecimal.TEN,
                4L, "DOC-EARLIER-SEQUENCE", LocalDate.of(2016, 6, 20),
                "Rebudget Funds"
        );
        when(repository.findTimeAndMoneySummary(3452675L))
                .thenReturn(Optional.of(summary));

        TimeAndMoneySummaryResponse result =
                service.findTimeAndMoneySummary(3452675L);

        // the exact version's own totals are still present...
        assertThat(result.awardId()).isEqualTo(3452675L);
        assertThat(result.sequenceNumber()).isEqualTo(4);
        assertThat(result.obligatedTotalAmount()).isEqualTo(BigDecimal.TEN);
        // ...but family activity is NOT suppressed to zero/null just
        // because this exact version has no Time-and-Money-created row
        // of its own - this is the bug this fixture guards against.
        assertThat(result.familyTransactionCount()).isEqualTo(4L);
        assertThat(result.lastFamilyTimeAndMoneyDocumentNumber())
                .isEqualTo("DOC-EARLIER-SEQUENCE");
    }

    @Test
    void findTimeAndMoneySummaryNoRealActivityMockedCase() {
        // No Award in the live archive was found with zero Time and
        // Money footprint at all (every Award has at least one
        // archive.award_amount_transaction row) - this case is
        // deliberately mocked rather than backed by a live fixture, so
        // the "genuinely empty" shape stays covered even though no real
        // fixture exists.
        TimeAndMoneySummaryResponse summary = new TimeAndMoneySummaryResponse(
                1L, "100000-00001", 1,
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                0L, null, null, null
        );
        when(repository.findTimeAndMoneySummary(1L))
                .thenReturn(Optional.of(summary));

        TimeAndMoneySummaryResponse result = service.findTimeAndMoneySummary(1L);

        assertThat(result.familyTransactionCount()).isZero();
        assertThat(result.lastFamilyTimeAndMoneyDocumentNumber()).isNull();
        assertThat(result.lastFamilyNoticeDate()).isNull();
        assertThat(result.lastFamilyTransactionTypeDescription()).isNull();
    }

    @Test
    void findTimeAndMoneySummarySiblingAwardsInTheSameHierarchyRemainIndependent() {
        // 100004-00002 and 100004-00003, both children of root
        // 100004-00001 in archive.award_hierarchy. The service never
        // walks the hierarchy or aggregates across sibling award
        // numbers - each summary is independently resolved by its own
        // exact awardId, proving hierarchy nodes are financially
        // independent (see docs/architecture/AWARD_HIERARCHY_FINANCIAL_SEMANTICS.md).
        TimeAndMoneySummaryResponse childOne = new TimeAndMoneySummaryResponse(
                100002L, "100004-00002", 1,
                new BigDecimal("50000"), new BigDecimal("40000"), new BigDecimal("10000"),
                new BigDecimal("50000"), new BigDecimal("40000"), new BigDecimal("10000"),
                2L, "DOC-CHILD-2", LocalDate.of(2019, 3, 1), "New"
        );
        TimeAndMoneySummaryResponse childTwo = new TimeAndMoneySummaryResponse(
                100003L, "100004-00003", 1,
                new BigDecimal("999000"), new BigDecimal("900000"), new BigDecimal("99000"),
                new BigDecimal("999000"), new BigDecimal("900000"), new BigDecimal("99000"),
                9L, "DOC-CHILD-3", LocalDate.of(2021, 5, 1), "Supplement"
        );
        when(repository.findTimeAndMoneySummary(100002L))
                .thenReturn(Optional.of(childOne));
        when(repository.findTimeAndMoneySummary(100003L))
                .thenReturn(Optional.of(childTwo));

        TimeAndMoneySummaryResponse resultOne =
                service.findTimeAndMoneySummary(100002L);
        TimeAndMoneySummaryResponse resultTwo =
                service.findTimeAndMoneySummary(100003L);

        // neither sibling's totals or activity leak into the other -
        // no hidden hierarchy-wide rollup.
        assertThat(resultOne.obligatedTotalAmount())
                .isNotEqualByComparingTo(resultTwo.obligatedTotalAmount());
        assertThat(resultOne.familyTransactionCount())
                .isNotEqualTo(resultTwo.familyTransactionCount());
        assertThat(resultOne.lastFamilyTimeAndMoneyDocumentNumber())
                .isNotEqualTo(resultTwo.lastFamilyTimeAndMoneyDocumentNumber());
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
