package edu.bu.archive.application.award.report;

import edu.bu.archive.adapter.in.web.dto.PageResponse;
import edu.bu.archive.adapter.in.web.dto.award.AwardAmountHistoryResponse;
import edu.bu.archive.adapter.in.web.dto.award.AwardBudgetSummaryResponse;
import edu.bu.archive.adapter.in.web.dto.award.AwardCommentsResponse;
import edu.bu.archive.adapter.in.web.dto.award.AwardCustomDataResponse;
import edu.bu.archive.adapter.in.web.dto.award.AwardSummaryResponse;
import edu.bu.archive.adapter.in.web.dto.award.AwardTermsResponse;
import edu.bu.archive.application.award.AwardArchiveService;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.NoSuchElementException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/*
 * AwardReportService introduces no new business logic of its own - it
 * only calls AwardArchiveService's existing methods with the report's
 * target awardId and walks paginated sections page-by-page. These
 * tests verify that wiring: award_id-to-award_number family
 * resolution happens implicitly via the same service methods the
 * workspace UI uses, and multi-page sections are fully collected in
 * order rather than truncated to one page.
 */
class AwardReportServiceTest {

    private AwardArchiveService archiveService;
    private AwardReportService reportService;

    @BeforeEach
    void setUp() {
        archiveService = mock(AwardArchiveService.class);
        reportService = new AwardReportService(archiveService);
    }

    @Test
    void unknownAwardIdPropagatesNotFound() {
        when(archiveService.findSummary(999L))
                .thenThrow(new NoSuchElementException("Award not found: 999"));

        assertThatThrownBy(() -> reportService.buildReportData(999L))
                .isInstanceOf(NoSuchElementException.class);
    }

    @Test
    void buildReportDataResolvesTheFamilyAndWalksEveryPageOfPaginatedSections() {
        long awardId = 5000L;
        stubMinimalCollaborators(awardId);

        AwardAmountHistoryResponse pageZeroRow = amountRow(1);
        AwardAmountHistoryResponse pageOneRow = amountRow(2);

        when(archiveService.findAmounts(awardId, 0, 100))
                .thenReturn(new PageResponse<>(List.of(pageZeroRow), 0, 100, 2L, 2, true, false));
        when(archiveService.findAmounts(awardId, 1, 100))
                .thenReturn(new PageResponse<>(List.of(pageOneRow), 1, 100, 2L, 2, false, true));

        AwardReportData data = reportService.buildReportData(awardId);

        assertThat(data.amounts()).containsExactly(pageZeroRow, pageOneRow);
        assertThat(data.summary().awardNumber()).isEqualTo("900000-00001");
    }

    private void stubMinimalCollaborators(long awardId) {
        when(archiveService.findSummary(awardId)).thenReturn(summary(awardId));
        when(archiveService.findVersions(eq(awardId), org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.anyInt()))
                .thenReturn(new PageResponse<>(List.of(), 0, 100, 0L, 0, true, true));
        when(archiveService.findPeople(awardId)).thenReturn(List.of());
        when(archiveService.findFundingProposals(awardId)).thenReturn(List.of());
        when(archiveService.findFundingSubawards(awardId)).thenReturn(List.of());
        when(archiveService.findAssociatedNegotiations(awardId)).thenReturn(List.of());
        when(archiveService.findBudgetSummary(awardId)).thenReturn(emptyBudgetSummary(awardId));
        when(archiveService.findBudgetVersions(eq(awardId), org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.anyInt()))
                .thenReturn(new PageResponse<>(List.of(), 0, 100, 0L, 0, true, true));
        when(archiveService.findBudgetPeriods(awardId)).thenReturn(List.of());
        when(archiveService.findBudgetLineItems(eq(awardId), org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.anyInt()))
                .thenReturn(new PageResponse<>(List.of(), 0, 100, 0L, 0, true, true));
        when(archiveService.findBudgetPersonnel(eq(awardId), org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.anyInt()))
                .thenReturn(new PageResponse<>(List.of(), 0, 100, 0L, 0, true, true));
        when(archiveService.findTimeAndMoneySummary(awardId)).thenReturn(
                new edu.bu.archive.adapter.in.web.dto.award.TimeAndMoneySummaryResponse(
                        awardId, "900000-00001", 1, null, null, null, null, null, null, 0L, null, null, null
                )
        );
        when(archiveService.findTimeAndMoneyActions(eq(awardId), org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.anyInt()))
                .thenReturn(new PageResponse<>(List.of(), 0, 100, 0L, 0, true, true));
        when(archiveService.findTimeAndMoneyHistory(eq(awardId), org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.anyInt()))
                .thenReturn(new PageResponse<>(List.of(), 0, 100, 0L, 0, true, true));
        when(archiveService.findTerms(awardId)).thenReturn(new AwardTermsResponse(List.of(), List.of()));
        when(archiveService.findCustomData(awardId)).thenReturn(List.<AwardCustomDataResponse>of());
        when(archiveService.findComments(awardId)).thenReturn(new AwardCommentsResponse(List.of(), List.of()));
        when(archiveService.findSapTransmissions(eq(awardId), org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.anyInt()))
                .thenReturn(new PageResponse<>(List.of(), 0, 100, 0L, 0, true, true));
    }

    private static AwardSummaryResponse summary(long awardId) {
        return new AwardSummaryResponse(
                awardId, "900000-00001", 1, "Synthetic Award", "Active", "Test Sponsor", null,
                "Test PI", "Test Unit", LocalDate.of(2020, 1, 1), null, null, null,
                BigDecimal.TEN, BigDecimal.TEN, null, null, null, null, null, null, true, "DOC-1"
        );
    }

    private static AwardBudgetSummaryResponse emptyBudgetSummary(long awardId) {
        return new AwardBudgetSummaryResponse(
                awardId, "900000-00001", 1, null, null, null, null, null,
                null, null, null, null, null, null, null
        );
    }

    private static AwardAmountHistoryResponse amountRow(int sequenceNumber) {
        return new AwardAmountHistoryResponse(
                (long) sequenceNumber, 5000L, "900000-00001", sequenceNumber,
                BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ZERO, BigDecimal.ZERO,
                BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE, LocalDate.of(2020, 1, 1), "DOC-" + sequenceNumber, 1L
        );
    }
}
