package edu.bu.archive.adapter.in.web.dto.award;

import java.time.LocalDate;

/*
 * The reverse view of NegotiationWorkspacePage's "Associated Record" -
 * every archive.negotiation row whose negotiation_association_type_code
 * is 'AWD' and whose associated_document_id matches this Award's
 * award_number, family-wide. Unlike AwardFundingSubawardResponse,
 * Negotiation has no version/family concept of its own - each
 * negotiationId is its own root record and is always directly
 * navigable, so there is no exact-vs-current-version pair here.
 */
public record AwardAssociatedNegotiationResponse(
        Long negotiationId,
        String documentNumber,
        String negotiationStatusDescription,
        String negotiationAgreementTypeDescription,
        String negotiatorFullName,
        LocalDate negotiationStartDate,
        LocalDate negotiationEndDate
) {
}
