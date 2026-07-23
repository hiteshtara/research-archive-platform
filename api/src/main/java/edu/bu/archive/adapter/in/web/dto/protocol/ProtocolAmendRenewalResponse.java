package edu.bu.archive.adapter.in.web.dto.protocol;

import java.time.LocalDate;
import java.time.LocalDateTime;

public record ProtocolAmendRenewalResponse(
        Long protoAmendRenewalId,
        Long protocolId,
        Long sourceProtocolId,
        String protocolNumber,
        Integer sequenceNumber,
        String protoAmendRenNumber,
        LocalDate dateCreated,
        String summary,
        LocalDateTime sourceUpdateTimestamp,
        String sourceUpdateUser,
        Long sourceVersionNumber,
        String sourceObjectId
) {
}
