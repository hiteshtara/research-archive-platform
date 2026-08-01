package edu.bu.archive.adapter.in.web.dto.award;

import java.time.LocalDate;

public record AwardSapTransmissionRow(
        Long transmissionId,
        String awardNumber,
        Integer sequenceNumber,
        String initiatorId,
        String transmitterId,
        String successIndicator,
        LocalDate transmissionDate,
        String basisOfPaymentCode,
        Integer accountTypeCode,
        String sponsorCode,
        String methodOfPaymentCode,
        String documentNumber,
        String sentData,
        String returnedData
) {
}
