package edu.bu.archive.application.award.report;

import edu.bu.archive.adapter.in.web.dto.award.AwardAmountHistoryResponse;
import edu.bu.archive.adapter.in.web.dto.award.AwardAssociatedNegotiationResponse;
import edu.bu.archive.adapter.in.web.dto.award.AwardBudgetLineItemResponse;
import edu.bu.archive.adapter.in.web.dto.award.AwardBudgetPeriodResponse;
import edu.bu.archive.adapter.in.web.dto.award.AwardBudgetPersonnelResponse;
import edu.bu.archive.adapter.in.web.dto.award.AwardBudgetSummaryResponse;
import edu.bu.archive.adapter.in.web.dto.award.AwardBudgetVersionResponse;
import edu.bu.archive.adapter.in.web.dto.award.AwardCommentsResponse;
import edu.bu.archive.adapter.in.web.dto.award.AwardCustomDataResponse;
import edu.bu.archive.adapter.in.web.dto.award.AwardFundingProposalResponse;
import edu.bu.archive.adapter.in.web.dto.award.AwardFundingSubawardResponse;
import edu.bu.archive.adapter.in.web.dto.award.AwardPersonDetailResponse;
import edu.bu.archive.adapter.in.web.dto.award.AwardSapTransmissionResponse;
import edu.bu.archive.adapter.in.web.dto.award.AwardSummaryResponse;
import edu.bu.archive.adapter.in.web.dto.award.AwardTermsResponse;
import edu.bu.archive.adapter.in.web.dto.award.AwardVersionSummaryResponse;
import edu.bu.archive.adapter.in.web.dto.award.TimeAndMoneyActionResponse;
import edu.bu.archive.adapter.in.web.dto.award.TimeAndMoneyHistoryEntryResponse;
import edu.bu.archive.adapter.in.web.dto.award.TimeAndMoneySummaryResponse;

import java.time.Instant;
import java.util.List;

/*
 * Every field here is sourced exclusively from the same
 * AwardArchiveService methods the Award workspace UI already calls -
 * see AwardReportService. Deliberately excludes attachments, S3/
 * storage internals, Evidence Search results, and any AI-generated
 * text, per the Complete Award Report spec.
 */
public record AwardReportData(
        AwardSummaryResponse summary,
        List<AwardVersionSummaryResponse> versions,
        List<AwardPersonDetailResponse> people,
        List<AwardFundingProposalResponse> fundingProposals,
        List<AwardFundingSubawardResponse> fundingSubawards,
        List<AwardAssociatedNegotiationResponse> associatedNegotiations,
        List<AwardAmountHistoryResponse> amounts,
        AwardBudgetSummaryResponse budgetSummary,
        List<AwardBudgetVersionResponse> budgetVersions,
        List<AwardBudgetPeriodResponse> budgetPeriods,
        List<AwardBudgetLineItemResponse> budgetLineItems,
        List<AwardBudgetPersonnelResponse> budgetPersonnel,
        TimeAndMoneySummaryResponse timeAndMoneySummary,
        List<TimeAndMoneyActionResponse> timeAndMoneyActions,
        List<TimeAndMoneyHistoryEntryResponse> timeAndMoneyHistory,
        AwardTermsResponse terms,
        List<AwardCustomDataResponse> customData,
        AwardCommentsResponse comments,
        List<AwardSapTransmissionResponse> sapTransmissions,
        Instant generatedAt
) {
}
