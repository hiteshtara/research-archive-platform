package edu.bu.archive.adapter.in.web.dto.protocol;

import java.time.LocalDateTime;

public record ProtocolUnitResponse(
        Long protocolUnitsId,
        Long protocolPersonId,
        String protocolNumber,
        Integer sequenceNumber,
        String unitNumber,
        String leadUnitFlag,
        String personId,
        LocalDateTime sourceUpdateTimestamp,
        String sourceUpdateUser,
        Long sourceVersionNumber,
        String sourceObjectId
) {
}
