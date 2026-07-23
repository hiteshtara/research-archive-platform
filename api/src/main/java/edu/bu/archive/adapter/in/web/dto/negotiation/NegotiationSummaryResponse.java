package edu.bu.archive.adapter.in.web.dto.negotiation;

import java.time.LocalDate;

public record NegotiationSummaryResponse(
        Long negotiationId,
        String documentNumber,
        Long negotiationStatusId,
        String negotiationStatusCode,
        String negotiationStatusDescription,
        Long negotiationAgreementTypeId,
        String negotiationAgreementTypeCode,
        String negotiationAgreementTypeDescription,
        Long negotiationAssociationTypeId,
        String negotiationAssociationTypeCode,
        String negotiationAssociationTypeDescription,
        String associatedDocumentId,
        String negotiatorPersonId,
        String negotiatorFullName,
        LocalDate negotiationStartDate,
        LocalDate negotiationEndDate,
        LocalDate anticipatedAwardDate
) {
}
