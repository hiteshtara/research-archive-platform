package edu.bu.archive.adapter.in.web.dto;

import java.time.LocalDate;

public record IrbSummaryResponse(
        Long recordId,
        String studyId,
        String protocolBase,
        String protocolNumber,
        String title,
        String protocolType,
        String protocolStatus,
        LocalDate approvalDate,
        String piBuid,
        String piFullName,
        String piEmail,
        boolean piBuidMissing,
        boolean active
) {
}
