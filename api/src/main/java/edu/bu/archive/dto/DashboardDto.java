package edu.bu.archive.dto;

public record DashboardDto(
        long irb,
        long awards,
        long proposals,
        long negotiations,
        long subawards,
        long documents
) {
}
