package edu.bu.archive.application.negotiation;

import java.time.LocalDate;

/**
 * Structured Negotiation search filters.
 *
 * <p>Every field is optional. A null field imposes no condition at all;
 * supplied fields combine with AND, alongside the free-text {@code query}
 * which is itself just another ANDed condition. Filtering happens in
 * PostgreSQL, never in the UI, so {@code page}/{@code size} and the total
 * count stay consistent with what is actually shown.
 *
 * <p>The filterable set is deliberately narrower than the set of archived
 * fields. Anticipated Award Date (7 of 10,775 rows populated), Sponsor
 * Award ID (1 of 8,554), Prime Sponsor (36 of 8,554) and Principal
 * Investigator (Non-BU) (25 of 8,554) are display-only: their verified
 * source population is too sparse to justify a dedicated filter. Subaward
 * Organization is populated in 0 rows and is not surfaced at all.
 *
 * <p>Matching semantics per field, chosen from how each value is actually
 * used rather than applied uniformly:
 * <ul>
 *   <li>{@code status}, {@code agreementType}, {@code associationType} -
 *       exact match. These come from Kuali lookup tables and the UI offers
 *       them as a fixed choice, so a substring match would only create
 *       surprising partial hits.</li>
 *   <li>{@code associationId} - exact match. It identifies one record
 *       (an Award Number for Award-associated Negotiations).</li>
 *   <li>{@code negotiator}, {@code principalInvestigator} - contains,
 *       case-insensitive. People are searched by fragments of a name.</li>
 *   <li>{@code sponsor}, {@code leadUnit} - contains, case-insensitive,
 *       matched against BOTH the name and the code/number, so "Addgene"
 *       and "303630" both find the same Negotiation.</li>
 *   <li>date bounds - inclusive on both ends.</li>
 * </ul>
 */
public record NegotiationSearchFilters(
        String query,

        String status,
        String negotiator,
        String agreementType,
        String principalInvestigator,
        String sponsor,
        String leadUnit,
        String associationType,
        String associationId,

        LocalDate startDateFrom,
        LocalDate startDateTo,
        LocalDate endDateFrom,
        LocalDate endDateTo
) {

    /** No filters and no free-text term - the plain "browse all" case. */
    public static NegotiationSearchFilters none() {
        return new NegotiationSearchFilters(
                null, null, null, null, null, null, null, null, null,
                null, null, null, null);
    }

    /** Free text only, with no structured filters. */
    public static NegotiationSearchFilters ofQuery(String query) {
        return new NegotiationSearchFilters(
                query, null, null, null, null, null, null, null, null,
                null, null, null, null);
    }

    /**
     * Normalizes blank-to-null on construction so that a filter the user
     * cleared in the UI (which arrives as "") is treated identically to one
     * that was never supplied, rather than becoming a condition matching
     * everything or nothing depending on the operator.
     */
    public NegotiationSearchFilters {
        query = blankToNull(query);
        status = blankToNull(status);
        negotiator = blankToNull(negotiator);
        agreementType = blankToNull(agreementType);
        principalInvestigator = blankToNull(principalInvestigator);
        sponsor = blankToNull(sponsor);
        leadUnit = blankToNull(leadUnit);
        associationType = blankToNull(associationType);
        associationId = blankToNull(associationId);
    }

    private static String blankToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    /** True when at least one structured filter is present. */
    public boolean hasStructuredFilters() {
        return status != null
                || negotiator != null
                || agreementType != null
                || principalInvestigator != null
                || sponsor != null
                || leadUnit != null
                || associationType != null
                || associationId != null
                || startDateFrom != null
                || startDateTo != null
                || endDateFrom != null
                || endDateTo != null;
    }
}
