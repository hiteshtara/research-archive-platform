package edu.bu.archive.adapter.in.web.dto.protocol;

import java.time.LocalDate;
import java.time.LocalDateTime;

public record ProtocolVersionResponse(
        Long protocolId,
        String protocolNumber,
        Integer sequenceNumber,
        String documentNumber,
        String active,
        String protocolTypeCode,
        String protocolTypeDescription,
        String protocolStatusCode,
        String protocolStatusDescription,
        String title,
        String description,
        LocalDate initialSubmissionDate,
        LocalDate approvalDate,
        LocalDate expirationDate,
        LocalDate lastApprovalDate,
        LocalDateTime sourceUpdateTimestamp,
        String sourceUpdateUser
) {
}
