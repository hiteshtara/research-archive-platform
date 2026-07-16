package edu.bu.archive.adapter.in.web.dto;

import java.time.LocalDate;

public record IrbFamilyResponse(
        String protocolBase,
        long versionCount,
        Long latestProtocolId,
        String latestProtocolNumber,
        String latestTitle,
        String latestStatus,
        String latestType,
        String piId,
        String piEmail,
        LocalDate latestApprovalDate
) {
}
