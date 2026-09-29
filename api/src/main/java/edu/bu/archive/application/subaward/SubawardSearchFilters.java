package edu.bu.archive.application.subaward;

import java.time.LocalDate;

/**
 * Structured Subaward search filters.
 *
 * <p>Mirrors {@code NegotiationSearchFilters}: every field is optional, a
 * null field imposes no condition, supplied fields combine with AND
 * alongside the free-text query (including its FRN branch), and filtering
 * happens in PostgreSQL so paging and the total count describe the full
 * result set. Filters apply to each returned archive.subaward VERSION row,
 * which is this search's existing grain.
 *
 * <p>Matching semantics:
 * <ul>
 *   <li>{@code status} - exact, case-insensitive, on
 *       {@code status_description}, with or without its Kuali ordinal:
 *       "Executed" and "07. Executed" both match "07. Executed".</li>
 *   <li>{@code sponsor} - contains, case-insensitive, against the linked
 *       Award's sponsor name OR prime sponsor name (the only sponsor
 *       columns archived on a Subaward).</li>
 *   <li>{@code organizationId} - exact, case-insensitive. It identifies
 *       one subrecipient; no organization name is archived.</li>
 *   <li>date bounds on {@code start_date}/{@code end_date} - inclusive on
 *       both ends.</li>
 * </ul>
 */
public record SubawardSearchFilters(
        String status,
        String sponsor,
        String organizationId,
        LocalDate startDateFrom,
        LocalDate startDateTo,
        LocalDate endDateFrom,
        LocalDate endDateTo
) {

    public static SubawardSearchFilters none() {
        return new SubawardSearchFilters(null, null, null, null, null, null, null);
    }

    public SubawardSearchFilters {
        status = blankToNull(status);
        sponsor = blankToNull(sponsor);
        organizationId = blankToNull(organizationId);
    }

    private static String blankToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    public boolean hasStructuredFilters() {
        return status != null
                || sponsor != null
                || organizationId != null
                || startDateFrom != null
                || startDateTo != null
                || endDateFrom != null
                || endDateTo != null;
    }
}
