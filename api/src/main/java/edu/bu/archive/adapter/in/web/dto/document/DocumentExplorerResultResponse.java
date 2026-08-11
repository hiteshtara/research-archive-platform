package edu.bu.archive.adapter.in.web.dto.document;

import java.time.LocalDate;

/*
 * One Kuali Document Explorer result row - exactly the approved result
 * model from docs/architecture/KUALI_DOCUMENT_EXPLORER_DESIGN.md's
 * "Result model" section. Multiple units/people/sponsors never produce
 * duplicate visible rows for the same (module, documentNumber) - the
 * *Count fields tell the UI how many additional relationships exist
 * beyond the single primary value shown here.
 */
public record DocumentExplorerResultResponse(
        String module,
        String documentNumber,
        String businessRecordNumber,
        String title,
        String normalizedStatus,
        String nativeStatusCode,
        String nativeStatusDescription,
        String versionOrSequence,
        String leadUnitNumber,
        String leadUnitName,
        String primaryPersonId,
        String primaryPersonName,
        String primaryPersonRole,
        String sponsorCode,
        String sponsorName,
        String subrecipientOrganizationId,
        String subrecipientOrganizationName,
        LocalDate documentDate,
        String targetRoute,
        int unitCount,
        int personCount,
        int sponsorCount
) {
}
