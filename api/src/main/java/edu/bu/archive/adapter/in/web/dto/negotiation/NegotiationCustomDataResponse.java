package edu.bu.archive.adapter.in.web.dto.negotiation;

import java.time.LocalDateTime;

public record NegotiationCustomDataResponse(
        Long negotiationCustomDataId,
        Long negotiationId,
        String negotiationNumber,
        Long customAttributeId,
        String value,
        LocalDateTime sourceUpdateTimestamp,
        String sourceUpdateUser,
        Long sourceVersionNumber,
        String sourceObjectId
) {
}
