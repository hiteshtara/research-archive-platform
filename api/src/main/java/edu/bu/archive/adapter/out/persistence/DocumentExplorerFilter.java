package edu.bu.archive.adapter.out.persistence;

import java.time.LocalDate;

/*
 * Normalized filter values for DocumentExplorerRepository - every
 * String field is "" (never null) to mean "no filter", matching
 * DocumentSearchRepository's own convention. Built exclusively by
 * DocumentExplorerService.normalize(); the repository never re-trims
 * or re-validates these values itself.
 */
public record DocumentExplorerFilter(
        String documentNumber,
        String businessRecordNumber,
        String query,
        String module,
        String normalizedStatus,
        String nativeStatus,
        String unitNumber,
        boolean includeAnyUnit,
        String personId,
        String personName,
        String personRole,
        boolean piOnly,
        String sponsorCode,
        String subrecipientOrganizationId,
        LocalDate dateFrom,
        LocalDate dateTo,
        String sort
) {
}
