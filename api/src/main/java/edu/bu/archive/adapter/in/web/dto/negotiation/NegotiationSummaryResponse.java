package edu.bu.archive.adapter.in.web.dto.negotiation;

import java.time.LocalDate;

/*
 * One Negotiation search/list row.
 *
 * Field names follow the archive columns; the Kuali business LABELS live
 * in the UI, which is where BU staff read them:
 *
 *   principalInvestigatorName -> "Principal Investigator (BU)"
 *   leadUnitName/leadUnitNumber -> "Lead Unit"
 *   associatedDocumentId -> "Negotiation Association ID"
 *
 * The resolved-attribute block below (title through attributeSource) does
 * NOT come from archive.negotiation. It comes from
 * archive.negotiation_search_attribute, which resolves each value from
 * whichever source actually holds it.
 *
 * WHY THAT MATTERS: archive.negotiation_unassociated_detail is not a
 * per-negotiation attributes table. It covers 8,554 of 10,775
 * Negotiations. Award-associated Negotiations carry their Title, PI,
 * Sponsor and Lead Unit on the associated Award instead - only 21 of
 * 2,223 of them have a detail row. Reading these fields from the detail
 * table alone silently blanks and silently excludes 20.6% of the archive
 * while still reporting a correct-looking total count. See V080's
 * migration header for the measured evidence.
 *
 * attributeSource (UNASSOCIATED_DETAIL | AWARD | NONE) is carried through
 * to the UI so a row can be honest about where its values came from,
 * rather than implying every Negotiation resolved the same way. NONE is a
 * real, expected outcome for Subaward- and Institutional-Proposal-
 * associated Negotiations (19 records), which have no detail row and no
 * fallback - they keep a row and render blank, they are never dropped.
 *
 * Deliberately absent:
 *   - Negotiation Age in Days: not requested by BU, and no stored source
 *     column exists for it. Its presence on the legacy Kuali screen does
 *     not make it a requirement.
 *   - Subaward Organization: NEGOTIATION_UNASSOC_DETAIL.SUBAWARD_ORG is
 *     populated in 0 of 8,554 Oracle rows - a permanently empty field,
 *     same precedent as Federal Award Year in Award V078.
 */
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
        LocalDate anticipatedAwardDate,

        // Resolved attributes - see the class comment above.
        String title,
        String principalInvestigatorName,
        String sponsorCode,
        String sponsorName,
        String leadUnitNumber,
        String leadUnitName,
        String attributeSource
) {
}
