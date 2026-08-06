package edu.bu.archive.adapter.in.web.dto.negotiation;

/*
 * Resolves Negotiation.associated_document_id into a navigable record,
 * per negotiation_association_type_code - proven live against real
 * Oracle data 2026-08-06, not guessed:
 *   AWD (Award)               -> associated_document_id = award_number
 *   IP  (Institutional Proposal) -> associated_document_id = proposal_number
 *   SWD (Subaward)             -> associated_document_id = subaward_id (already the internal PK)
 *   NO  (no association)        -> the dominant case (8,533/10,775 live) - never a link
 * Any other code is presented but not resolved (clickable = false),
 * rather than guessed at.
 */
public record NegotiationAssociatedRecordResponse(
        String associationTypeCode,
        String associationTypeDescription,
        String associatedDocumentId,
        String kind,
        Long navigableId,
        boolean clickable
) {
}
