package edu.bu.archive.adapter.in.web.dto.protocol;

import java.time.LocalDate;

public record ProtocolSummaryResponse(
        String protocolNumber,
        Long versionCount,
        Long latestProtocolId,
        Integer latestSequenceNumber,
        String title,
        String protocolStatusDescription,
        String protocolTypeDescription,
        String active,
        LocalDate expirationDate
) {
}
