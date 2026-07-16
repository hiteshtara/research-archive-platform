package edu.bu.archive.dto;

public record DashboardDto(
        long irb,
        long protocolFamilies,
        long protocolVersions,
        long submissions,
        long fundingRecords,
        long timelineEvents,
        long awards,
        long proposals,
        long negotiations,
        long subawards,
        long documents
) {
}
