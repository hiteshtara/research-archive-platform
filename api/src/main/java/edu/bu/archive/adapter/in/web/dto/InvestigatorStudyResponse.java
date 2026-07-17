package edu.bu.archive.adapter.in.web.dto;

import java.time.LocalDate;

public record InvestigatorStudyResponse(
        Long recordId,
        Long protocolId,
        String protocolBase,
        String protocolNumber,
        String title,
        String status,
        String recordType,
        LocalDate approvalDate
) {
}
