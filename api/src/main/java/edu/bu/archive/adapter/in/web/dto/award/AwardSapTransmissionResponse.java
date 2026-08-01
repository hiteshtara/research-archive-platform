package edu.bu.archive.adapter.in.web.dto.award;

import java.time.LocalDate;
import java.util.List;

/*
 * archive.award_transmission - one row per transmission ATTEMPT, both
 * success and failure preserved (see V052's header comment). successful
 * is derived from success_indicator using this codebase's existing Y/N
 * flag convention (see AwardArchiveService.findCurrentFunding's
 * activeFlag handling) - successIndicator itself is also exposed
 * unparsed since its raw values are not independently confirmed.
 * sentData/returnedData are the raw SOAP XML payloads, verbatim - a
 * client must render them as text, never as HTML.
 */
public record AwardSapTransmissionResponse(
        Long transmissionId,
        String awardNumber,
        Integer sequenceNumber,
        String initiatorId,
        String transmitterId,
        String successIndicator,
        boolean successful,
        LocalDate transmissionDate,
        String basisOfPaymentCode,
        Integer accountTypeCode,
        String sponsorCode,
        String methodOfPaymentCode,
        String documentNumber,
        String sentData,
        String returnedData,
        List<AwardSapTransmissionChildResponse> children
) {
}
