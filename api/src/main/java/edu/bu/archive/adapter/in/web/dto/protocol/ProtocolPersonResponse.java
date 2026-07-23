package edu.bu.archive.adapter.in.web.dto.protocol;

import java.time.LocalDateTime;
import java.util.List;

public record ProtocolPersonResponse(
        Long protocolPersonId,
        Long protocolId,
        Long sourceProtocolId,
        String protocolNumber,
        Integer sequenceNumber,
        String personId,
        String personName,
        String protocolPersonRoleId,
        Long rolodexId,
        String affiliationTypeCode,
        String comments,
        LocalDateTime sourceUpdateTimestamp,
        String sourceUpdateUser,
        Long sourceVersionNumber,
        String sourceObjectId,
        List<ProtocolUnitResponse> units
) {
}
