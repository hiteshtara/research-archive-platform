package edu.bu.archive.adapter.in.web.dto.award;

public record AwardSapTransmissionChildResponse(
        Long transmissionChildId,
        String awardNumber,
        Integer sequenceNumber,
        String parentDocumentNumber,
        String childDocumentNumber,
        String leadUnitNumber,
        String childType,
        String overheadKey,
        String baseCode,
        String offCampus
) {
}
