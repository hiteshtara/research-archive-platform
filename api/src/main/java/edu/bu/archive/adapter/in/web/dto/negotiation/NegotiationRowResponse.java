package edu.bu.archive.adapter.in.web.dto.negotiation;

import java.time.LocalDate;
import java.time.LocalDateTime;

public record NegotiationRowResponse(
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
        String negotiatorPersonId,
        String negotiatorFullName,
        LocalDate negotiationStartDate,
        LocalDate negotiationEndDate,
        LocalDate anticipatedAwardDate,
        String documentFolder,
        String associatedDocumentId,
        LocalDateTime sourceUpdateTimestamp,
        String sourceUpdateUser,
        Long sourceVersionNumber,
        String sourceObjectId,
        LocalDateTime documentSourceUpdateTimestamp,
        String documentSourceUpdateUser,
        Long documentSourceVersionNumber,
        String documentSourceObjectId
) {
}
