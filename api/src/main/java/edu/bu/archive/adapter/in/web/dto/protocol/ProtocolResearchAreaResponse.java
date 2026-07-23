package edu.bu.archive.adapter.in.web.dto.protocol;

import java.time.LocalDateTime;

public record ProtocolResearchAreaResponse(
        Long protocolResearchAreaId,
        Long protocolId,
        Long sourceProtocolId,
        String protocolNumber,
        Integer sequenceNumber,
        String researchAreaCode,
        LocalDateTime sourceUpdateTimestamp,
        String sourceUpdateUser,
        Long sourceVersionNumber,
        String sourceObjectId
) {
}
