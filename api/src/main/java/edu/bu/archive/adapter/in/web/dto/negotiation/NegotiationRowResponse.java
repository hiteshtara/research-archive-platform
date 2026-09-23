package edu.bu.archive.adapter.in.web.dto.negotiation;

import java.time.LocalDate;
import java.time.LocalDateTime;

/*
 * The Negotiation workspace row.
 *
 * The resolved attributes at the end - title, PI, sponsor, prime
 * sponsor, lead unit, sponsor award number - are NOT computed here and
 * are NOT re-derived from NEGOTIATION_UNASSOC_DETAIL or the associated
 * Award. They are read straight out of
 * archive.negotiation_search_attribute, the table the V080 rebuild
 * materializes, so the workspace shows exactly what the list/search
 * page shows for the same Negotiation. Field names are deliberately the
 * same ones NegotiationSummaryResponse already uses - synonyms would
 * invite the two endpoints to drift.
 *
 * attributeSource says which source the rebuild resolved from ("AWARD",
 * "UNASSOCIATED_DETAIL", or "NONE"); the precedence rule itself lives in
 * the ETL, never in Java or React.
 */
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
        String documentSourceObjectId,

        // Materialized by the V080 rebuild - read, never recomputed.
        String title,
        String principalInvestigatorName,
        String sponsorCode,
        String sponsorName,
        String primeSponsorCode,
        String primeSponsorName,
        String leadUnitNumber,
        String leadUnitName,
        String sponsorAwardNumber,
        String attributeSource
) {
}
