package edu.bu.archive.adapter.in.web.dto.protocol;

import java.time.LocalDateTime;

public record ProtocolLocationResponse(
        Long protocolLocationId,
        Long protocolId,
        Long sourceProtocolId,
        String protocolNumber,
        Integer sequenceNumber,
        String parentResolutionMethod,
        String protocolOrgTypeCode,
        String organizationId,
        Long rolodexId,
        LocalDateTime sourceUpdateTimestamp,
        String sourceUpdateUser,
        Long sourceVersionNumber,
        String sourceObjectId
) {
}
