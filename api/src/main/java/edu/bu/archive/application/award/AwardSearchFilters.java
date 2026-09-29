package edu.bu.archive.application.award;

import java.time.LocalDate;

/**
 * Structured Award search filters, shared by the current-Award (family)
 * search and the Historical Award Records (version) search.
 *
 * <p>Mirrors {@code NegotiationSearchFilters}: every field is optional, a
 * null field imposes no condition, supplied fields combine with AND
 * alongside the free-text query, and filtering happens in PostgreSQL so
 * paging and the total count always describe the full result set.
 *
 * <p>Each filter applies to the row being returned - the current version
 * for Award search, each individual version for Historical Award Records -
 * never family-wide.
 *
 * <p>Matching semantics, chosen to match Negotiations field-for-field:
 * <ul>
 *   <li>{@code status} - exact, case-insensitive, on
 *       {@code status_description}. A substring match would make "Active"
 *       also match "Inactive".</li>
 *   <li>{@code sponsor}, {@code leadUnit} - contains, case-insensitive,
 *       against BOTH the name and the code/number.</li>
 *   <li>{@code principalInvestigator} - contains, case-insensitive, against
 *       {@code award_person} rows whose contact role is PI on that
 *       version.</li>
 *   <li>{@code projectStartDateFrom/To} - Kuali's "Project Start Date",
 *       which is {@code award_effective_date} (never {@code begin_date},
 *       populated on 2 of 267,386 rows). Inclusive on both ends.</li>
 * </ul>
 */
public record AwardSearchFilters(
        String status,
        String sponsor,
        String principalInvestigator,
        String leadUnit,
        LocalDate projectStartDateFrom,
        LocalDate projectStartDateTo
) {

    public static AwardSearchFilters none() {
        return new AwardSearchFilters(null, null, null, null, null, null);
    }

    /**
     * Blank-to-null on construction, so a filter the user cleared (which
     * arrives as "") is identical to one never supplied.
     */
    public AwardSearchFilters {
        status = blankToNull(status);
        sponsor = blankToNull(sponsor);
        principalInvestigator = blankToNull(principalInvestigator);
        leadUnit = blankToNull(leadUnit);
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
                || principalInvestigator != null
                || leadUnit != null
                || projectStartDateFrom != null
                || projectStartDateTo != null;
    }
}
