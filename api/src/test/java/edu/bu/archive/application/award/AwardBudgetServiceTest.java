package edu.bu.archive.application.award;

import edu.bu.archive.adapter.in.web.dto.PageResponse;
import edu.bu.archive.adapter.in.web.dto.award.AwardBudgetLineItemResponse;
import edu.bu.archive.adapter.in.web.dto.award.AwardBudgetPeriodResponse;
import edu.bu.archive.adapter.in.web.dto.award.AwardBudgetPersonnelResponse;
import edu.bu.archive.adapter.in.web.dto.award.AwardBudgetRow;
import edu.bu.archive.adapter.in.web.dto.award.AwardBudgetSummaryResponse;
import edu.bu.archive.adapter.in.web.dto.award.AwardBudgetVersionResponse;
import edu.bu.archive.adapter.in.web.dto.award.AwardFamilyPositionRow;
import edu.bu.archive.adapter.out.persistence.AwardArchiveRepository;
import edu.bu.archive.adapter.out.persistence.AwardAttachmentStorage;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/*
 * Service-layer tests for AwardArchiveService's Budget methods - see
 * docs/kuali-business-rules/Budget.md. Uses the real, live-verified
 * fixture (award_number 103692-00002, 26 Award sequences, 38 Budget
 * versions) throughout, the same precedent TimeAndMoneyServiceTest
 * already established for its own regression fixtures.
 */
class AwardBudgetServiceTest {

    private AwardArchiveRepository repository;
    private AwardArchiveService service;

    private static final String AWARD_NUMBER = "103692-00002";

    @BeforeEach
    void setUp() {
        repository = mock(AwardArchiveRepository.class);
        service = new AwardArchiveService(
                repository, mock(AwardAttachmentStorage.class)
        );
    }

    private static AwardBudgetRow row(
            long budgetId,
            long owningAwardId,
            int owningSequence,
            int budgetVersionNumber,
            String statusCode,
            String statusDescription
    ) {
        return row(
                budgetId, owningAwardId, owningSequence, budgetVersionNumber,
                statusCode, statusDescription, BigDecimal.valueOf(11), null, null
        );
    }

    private static AwardBudgetRow row(
            long budgetId,
            long owningAwardId,
            int owningSequence,
            int budgetVersionNumber,
            String statusCode,
            String statusDescription,
            BigDecimal totalCost,
            BigDecimal awardBudgetTotalCostLimit,
            BigDecimal budgetChangeTotalCostLimit
    ) {
        return new AwardBudgetRow(
                budgetId, owningAwardId, owningSequence, budgetVersionNumber,
                String.valueOf(budgetId + 1_000_000), statusCode, statusDescription,
                LocalDate.of(2020, 1, 1), LocalDate.of(2021, 1, 1),
                BigDecimal.TEN, BigDecimal.ONE, totalCost,
                awardBudgetTotalCostLimit, budgetChangeTotalCostLimit
        );
    }

    // --- Summary: selectedArchiveBudget rule ------------------------------

    @Test
    void findBudgetSummarySelectsTheHighestPostedVersionOverAHigherCancelledOne() {
        when(repository.findFamilyPositionForId(3831872L))
                .thenReturn(Optional.of(new AwardFamilyPositionRow(AWARD_NUMBER, 46)));
        // Real shape from the fixture: a Posted version can sit BELOW
        // a later Cancelled one - Posted must still win.
        when(repository.findBudgetsInScope(AWARD_NUMBER, 46)).thenReturn(List.of(
                row(213642L, 3831872L, 46, 38, "14", "Cancelled"),
                row(213641L, 3831872L, 46, 37, "9", "Posted")
        ));

        AwardBudgetSummaryResponse summary = service.findBudgetSummary(3831872L);

        assertThat(summary.selectedBudgetId()).isEqualTo(213641L);
        assertThat(summary.selectedBudgetVersionNumber()).isEqualTo(37);
        assertThat(summary.statusCode()).isEqualTo("9");
        assertThat(summary.awardNumber()).isEqualTo(AWARD_NUMBER);
        assertThat(summary.viewedSequenceNumber()).isEqualTo(46);
    }

    @Test
    void findBudgetSummaryFallsBackToHighestNonCancelledWhenNothingIsPosted() {
        when(repository.findFamilyPositionForId(881365L))
                .thenReturn(Optional.of(new AwardFamilyPositionRow(AWARD_NUMBER, 14)));
        when(repository.findBudgetsInScope(AWARD_NUMBER, 14)).thenReturn(List.of(
                row(146587L, 881365L, 14, 9, "14", "Cancelled"),
                row(146583L, 881365L, 14, 5, "10", "To Be Posted"),
                row(146579L, 881365L, 14, 1, "9", "Posted")
        ));
        // Note: this scenario intentionally omits a Posted row above
        // version 5 to prove the fallback path specifically.

        AwardBudgetSummaryResponse summary = service.findBudgetSummary(881365L);

        // Highest Posted (version 1) still wins over the fallback tier
        // - the rule always tries Posted first regardless of version.
        assertThat(summary.selectedBudgetVersionNumber()).isEqualTo(1);
    }

    @Test
    void findBudgetSummaryUsesHighestNonCancelledWhenTrulyNoPostedVersionExists() {
        when(repository.findFamilyPositionForId(881365L))
                .thenReturn(Optional.of(new AwardFamilyPositionRow(AWARD_NUMBER, 14)));
        when(repository.findBudgetsInScope(AWARD_NUMBER, 14)).thenReturn(List.of(
                row(146587L, 881365L, 14, 9, "14", "Cancelled"),
                row(146583L, 881365L, 14, 5, "10", "To Be Posted")
        ));

        AwardBudgetSummaryResponse summary = service.findBudgetSummary(881365L);

        assertThat(summary.selectedBudgetId()).isEqualTo(146583L);
        assertThat(summary.selectedBudgetVersionNumber()).isEqualTo(5);
        assertThat(summary.statusCode()).isEqualTo("10");
    }

    // --- Budget semantic fix: awardBudgetTotalCostLimit/budgetChangeTotalCostLimit ---
    // Real fixture, live-verified against Kuali and the archive (see
    // docs/kuali-business-rules/Budget.md): Award 105698-00002, budget_id
    // 176666 (version 5, "closeout" doc, sequence 9/award_id 2280896).
    // Kuali's screen shows "Budget Total Cost Limit: 699,246.57" and
    // "Budget Change Total Cost Limit: 0.01" alongside this version's own
    // Total of 0.01 - three distinct numbers, all persisted verbatim on
    // archive.award_budget (obligated_total/total_cost_limit/total_cost).

    private static final String LIMIT_FIXTURE_AWARD_NUMBER = "105698-00002";

    @Test
    void findBudgetSummaryExposesTheAwardLevelLimitSnapshotsFromTheSelectedVersionVerbatim() {
        when(repository.findFamilyPositionForId(2280896L))
                .thenReturn(Optional.of(new AwardFamilyPositionRow(LIMIT_FIXTURE_AWARD_NUMBER, 9)));
        when(repository.findBudgetsInScope(LIMIT_FIXTURE_AWARD_NUMBER, 9)).thenReturn(List.of(
                row(176666L, 2280896L, 9, 5, "9", "Posted",
                        new BigDecimal("0.01"),
                        new BigDecimal("699246.57"),
                        new BigDecimal("0.01"))
        ));

        AwardBudgetSummaryResponse summary = service.findBudgetSummary(2280896L);

        assertThat(summary.totalCost()).isEqualByComparingTo("0.01");
        assertThat(summary.awardBudgetTotalCostLimit()).isEqualByComparingTo("699246.57");
        assertThat(summary.budgetChangeTotalCostLimit()).isEqualByComparingTo("0.01");
    }

    @Test
    void findBudgetSummaryLeavesOnlyTheAwardLevelCeilingNullForAConvertedVersionWhereItWasNeverSet() {
        // Real fixture, live-verified against the deployed API: this
        // award's own version 4 ("Converted Budget Document"/pre-snapshot
        // amendment) never had obligated_total (awardBudgetTotalCostLimit)
        // populated in Kuali - archived as null, not 0. total_cost_limit
        // (budgetChangeTotalCostLimit) is a SEPARATE column and WAS
        // populated on this same version (-27627.44, equal to its own
        // total_cost since no prior Posted budget existed yet to
        // subtract) - the two snapshots must never be assumed to be
        // null/non-null together.
        when(repository.findFamilyPositionForId(558547L))
                .thenReturn(Optional.of(new AwardFamilyPositionRow(LIMIT_FIXTURE_AWARD_NUMBER, 8)));
        when(repository.findBudgetsInScope(LIMIT_FIXTURE_AWARD_NUMBER, 8)).thenReturn(List.of(
                row(126808L, 558547L, 8, 4, "9", "Posted",
                        new BigDecimal("-27627.44"), null, new BigDecimal("-27627.44"))
        ));

        AwardBudgetSummaryResponse summary = service.findBudgetSummary(558547L);

        assertThat(summary.totalCost()).isEqualByComparingTo("-27627.44");
        assertThat(summary.awardBudgetTotalCostLimit()).isNull();
        assertThat(summary.budgetChangeTotalCostLimit()).isEqualByComparingTo("-27627.44");
    }

    @Test
    void findBudgetVersionsCarriesTheLimitSnapshotsThroughForEveryVersionInScope() {
        when(repository.findFamilyPositionForId(2280896L))
                .thenReturn(Optional.of(new AwardFamilyPositionRow(LIMIT_FIXTURE_AWARD_NUMBER, 9)));
        List<AwardBudgetRow> rows = List.of(
                row(176666L, 2280896L, 9, 5, "9", "Posted",
                        new BigDecimal("0.01"), new BigDecimal("699246.57"), new BigDecimal("0.01")),
                row(126808L, 558547L, 8, 4, "9", "Posted",
                        new BigDecimal("-27627.44"), null, new BigDecimal("-27627.44"))
        );
        when(repository.findBudgetsInScope(LIMIT_FIXTURE_AWARD_NUMBER, 9)).thenReturn(rows);

        PageResponse<AwardBudgetVersionResponse> page =
                service.findBudgetVersions(2280896L, 0, 20);

        AwardBudgetVersionResponse v5 = page.content().stream()
                .filter(v -> v.budgetVersionNumber() == 5).findFirst().orElseThrow();
        assertThat(v5.awardBudgetTotalCostLimit()).isEqualByComparingTo("699246.57");
        assertThat(v5.budgetChangeTotalCostLimit()).isEqualByComparingTo("0.01");
        assertThat(v5.selected()).isTrue();

        AwardBudgetVersionResponse v4 = page.content().stream()
                .filter(v -> v.budgetVersionNumber() == 4).findFirst().orElseThrow();
        assertThat(v4.awardBudgetTotalCostLimit()).isNull();
        assertThat(v4.budgetChangeTotalCostLimit()).isEqualByComparingTo("-27627.44");
    }

    @Test
    void findBudgetSummaryReturnsAllNullFieldsWhenEveryBudgetInScopeIsCancelled() {
        when(repository.findFamilyPositionForId(881365L))
                .thenReturn(Optional.of(new AwardFamilyPositionRow(AWARD_NUMBER, 14)));
        when(repository.findBudgetsInScope(AWARD_NUMBER, 14)).thenReturn(List.of(
                row(146587L, 881365L, 14, 9, "14", "Cancelled")
        ));

        AwardBudgetSummaryResponse summary = service.findBudgetSummary(881365L);

        assertThat(summary.selectedBudgetId()).isNull();
        assertThat(summary.selectedBudgetVersionNumber()).isNull();
        assertThat(summary.statusCode()).isNull();
        assertThat(summary.totalCost()).isNull();
        // Real Award context is preserved even with no valid Budget.
        assertThat(summary.awardNumber()).isEqualTo(AWARD_NUMBER);
    }

    @Test
    void findBudgetSummaryReturnsAllNullFieldsWhenNoBudgetExistsAtAll() {
        when(repository.findFamilyPositionForId(881365L))
                .thenReturn(Optional.of(new AwardFamilyPositionRow(AWARD_NUMBER, 14)));
        when(repository.findBudgetsInScope(AWARD_NUMBER, 14)).thenReturn(List.of());

        AwardBudgetSummaryResponse summary = service.findBudgetSummary(881365L);

        assertThat(summary.selectedBudgetId()).isNull();
    }

    @Test
    void findBudgetSummaryThrowsNotFoundForAMissingAwardId() {
        when(repository.findFamilyPositionForId(999L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.findBudgetSummary(999L))
                .isInstanceOf(NoSuchElementException.class);
    }

    // --- Historical-sequence bounding -------------------------------------

    @Test
    void viewingAnEarlierAwardSequenceOnlySeesBudgetVersionsThroughThatSequence() {
        // award_id 1226449 is sequence 16 of the real fixture - viewing
        // it must bound the repository call to sequence 16, not the
        // family's full 46.
        when(repository.findFamilyPositionForId(1226449L))
                .thenReturn(Optional.of(new AwardFamilyPositionRow(AWARD_NUMBER, 16)));
        when(repository.findBudgetsInScope(AWARD_NUMBER, 16)).thenReturn(List.of(
                row(161079L, 1226449L, 16, 14, "9", "Posted")
        ));

        service.findBudgetSummary(1226449L);

        // Bounded to sequence 16, never the family's later sequences
        // (e.g. 46) - proves the "historical Award version sees fewer
        // Budget versions, never more" rule from the design doc.
        org.mockito.Mockito.verify(repository)
                .findBudgetsInScope(AWARD_NUMBER, 16);
    }

    // --- Versions: family-wide, not exact-awardId, with a "selected" flag -

    @Test
    void findBudgetVersionsSpansSiblingAwardIdsWithinTheFamily() {
        when(repository.findFamilyPositionForId(3831872L))
                .thenReturn(Optional.of(new AwardFamilyPositionRow(AWARD_NUMBER, 46)));
        when(repository.findBudgetsInScope(AWARD_NUMBER, 46)).thenReturn(List.of(
                row(213642L, 3831872L, 46, 38, "9", "Posted"),
                row(180665L, 2335627L, 26, 23, "9", "Posted"),
                row(146579L, 881365L, 14, 1, "9", "Posted")
        ));

        PageResponse<AwardBudgetVersionResponse> versions =
                service.findBudgetVersions(3831872L, 0, 50);

        assertThat(versions.content()).hasSize(3);
        assertThat(versions.content())
                .extracting(AwardBudgetVersionResponse::owningAwardId)
                .containsExactly(3831872L, 2335627L, 881365L);
        // Highest version (38, budgetId 213642) is the selected one.
        assertThat(versions.content().get(0).selected()).isTrue();
        assertThat(versions.content().get(1).selected()).isFalse();
        assertThat(versions.content().get(2).selected()).isFalse();
    }

    @Test
    void findBudgetVersionsPaginatesOverTheFullFamilyWideSet() {
        when(repository.findFamilyPositionForId(3831872L))
                .thenReturn(Optional.of(new AwardFamilyPositionRow(AWARD_NUMBER, 46)));
        List<AwardBudgetRow> thirtyEightVersions = java.util.stream.IntStream
                .rangeClosed(1, 38)
                .mapToObj(version -> row(
                        200_000L + version, 3831872L, 46, version, "9", "Posted"
                ))
                .sorted((a, b) -> b.budgetVersionNumber() - a.budgetVersionNumber())
                .toList();
        when(repository.findBudgetsInScope(AWARD_NUMBER, 46))
                .thenReturn(thirtyEightVersions);

        PageResponse<AwardBudgetVersionResponse> firstPage =
                service.findBudgetVersions(3831872L, 0, 25);
        PageResponse<AwardBudgetVersionResponse> secondPage =
                service.findBudgetVersions(3831872L, 1, 25);

        assertThat(firstPage.content()).hasSize(25);
        assertThat(firstPage.totalElements()).isEqualTo(38);
        assertThat(secondPage.content()).hasSize(13);
    }

    // --- Periods/line items/personnel: scoped to the selected budget_id ---

    @Test
    void findBudgetPeriodsResolvesTheSelectedBudgetThenDelegatesToTheRepository() {
        when(repository.findFamilyPositionForId(3831872L))
                .thenReturn(Optional.of(new AwardFamilyPositionRow(AWARD_NUMBER, 46)));
        when(repository.findBudgetsInScope(AWARD_NUMBER, 46)).thenReturn(List.of(
                row(213642L, 3831872L, 46, 38, "9", "Posted")
        ));
        List<AwardBudgetPeriodResponse> periods = List.of(
                new AwardBudgetPeriodResponse(
                        1L, 1, LocalDate.of(2025, 1, 1), LocalDate.of(2025, 12, 31),
                        BigDecimal.TEN, BigDecimal.ONE, BigDecimal.valueOf(11)
                )
        );
        when(repository.findBudgetPeriods(213642L)).thenReturn(periods);

        assertThat(service.findBudgetPeriods(3831872L)).isEqualTo(periods);
    }

    @Test
    void findBudgetPeriodsReturnsEmptyWhenNoBudgetIsSelected() {
        when(repository.findFamilyPositionForId(881365L))
                .thenReturn(Optional.of(new AwardFamilyPositionRow(AWARD_NUMBER, 14)));
        when(repository.findBudgetsInScope(AWARD_NUMBER, 14)).thenReturn(List.of());

        assertThat(service.findBudgetPeriods(881365L)).isEmpty();
    }

    @Test
    void findBudgetLineItemsResolvesTheSelectedBudgetThenPaginates() {
        when(repository.findFamilyPositionForId(3831872L))
                .thenReturn(Optional.of(new AwardFamilyPositionRow(AWARD_NUMBER, 46)));
        when(repository.findBudgetsInScope(AWARD_NUMBER, 46)).thenReturn(List.of(
                row(213642L, 3831872L, 46, 38, "9", "Posted")
        ));
        when(repository.countBudgetLineItems(213642L)).thenReturn(3L);
        List<AwardBudgetLineItemResponse> lineItems = List.of(
                new AwardBudgetLineItemResponse(
                        1L, 1L, 1, "Supplies", "1000",
                        LocalDate.of(2025, 1, 1), LocalDate.of(2025, 12, 31),
                        BigDecimal.TEN, BigDecimal.ZERO
                )
        );
        when(repository.findBudgetLineItems(213642L, 50, 0)).thenReturn(lineItems);

        PageResponse<AwardBudgetLineItemResponse> page =
                service.findBudgetLineItems(3831872L, 0, 50);

        assertThat(page.content()).isEqualTo(lineItems);
        assertThat(page.totalElements()).isEqualTo(3L);
    }

    @Test
    void findBudgetPersonnelResolvesTheSelectedBudgetThenPaginates() {
        when(repository.findFamilyPositionForId(3831872L))
                .thenReturn(Optional.of(new AwardFamilyPositionRow(AWARD_NUMBER, 46)));
        when(repository.findBudgetsInScope(AWARD_NUMBER, 46)).thenReturn(List.of(
                row(213642L, 3831872L, 46, 38, "9", "Posted")
        ));
        when(repository.countBudgetPersonnel(213642L)).thenReturn(1L);
        List<AwardBudgetPersonnelResponse> personnel = List.of(
                new AwardBudgetPersonnelResponse(
                        1L, "P123", "Jane Doe", "1234", "Faculty",
                        BigDecimal.valueOf(50000), BigDecimal.valueOf(52000)
                )
        );
        when(repository.findBudgetPersonnel(213642L, 50, 0)).thenReturn(personnel);

        PageResponse<AwardBudgetPersonnelResponse> page =
                service.findBudgetPersonnel(3831872L, 0, 50);

        assertThat(page.content()).isEqualTo(personnel);
    }
}
