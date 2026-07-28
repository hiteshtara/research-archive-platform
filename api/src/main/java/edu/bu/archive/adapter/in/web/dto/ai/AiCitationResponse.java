package edu.bu.archive.adapter.in.web.dto.ai;

public record AiCitationResponse(
        String recordType,
        String recordId,
        String awardNumber,
        Integer sequenceNumber
) {
}
