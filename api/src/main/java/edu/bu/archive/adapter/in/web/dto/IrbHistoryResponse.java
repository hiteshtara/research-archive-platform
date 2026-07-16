package edu.bu.archive.adapter.in.web.dto;

import java.time.LocalDate;

public record IrbHistoryResponse(
        Long protocolId,
        String protocolBase,
        String protocolNumber,
        Integer sequenceNumber,
        String documentNumber,
        String crcProtocolNumber,
        String title,
        String protocolStatus,
        String protocolType,
        String piId,
        String piEmail,
        LocalDate approvalDate,
        LocalDate expirationDate
) {
}
