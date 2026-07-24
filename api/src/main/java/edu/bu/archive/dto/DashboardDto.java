package edu.bu.archive.dto;

public record DashboardDto(
        long irb,
        long protocolFamilies,
        long protocolVersions,
        long submissions,
        long fundingRecords,
        long timelineEvents,
        long awards,
        long awardHistoryRecords,
        long proposals,
        long proposalHistoryRecords,
        long negotiations,
        long subawards,
        long documents
) {
}
