package edu.bu.archive.adapter.out.persistence;

import java.time.LocalDate;

/*
 * Raw repository-level row from DocumentExplorerRepository's fixed
 * four-module union (Award/Proposal/Negotiation/Subaward - IRB
 * excluded, see docs/architecture/KUALI_DOCUMENT_EXPLORER_DESIGN.md
 * §16 decision 7). targetId is the module's own routable identifier;
 * DocumentExplorerService turns (module, targetId) into targetRoute so
 * routing rules live in one place, mirroring DocumentSearchRow's own
 * convention exactly.
 *
 * unitCount/personCount/sponsorCount are ADDITIONAL relationships
 * beyond the single primary value already shown on this row (e.g. an
 * Award with 3 recorded people has personCount=3, not personCount-1) -
 * the UI decides how to render "+N other" from these raw counts.
 */
public record DocumentExplorerRow(
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
        String targetId,
        int unitCount,
        int personCount,
        int sponsorCount
) {
}
