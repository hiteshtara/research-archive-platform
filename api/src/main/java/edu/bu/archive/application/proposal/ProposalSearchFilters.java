package edu.bu.archive.application.proposal;

/**
 * Structured Proposal family search filters.
 *
 * <p>Mirrors {@code NegotiationSearchFilters}: optional, blank-to-null,
 * ANDed with each other and with the free-text query, applied in
 * PostgreSQL. Each filter applies to the family's LATEST version - the
 * same row whose values the result card displays.
 *
 * <p>Matching: {@code sponsor} and {@code leadUnit} are contains,
 * case-insensitive, against BOTH name and code/number;
 * {@code principalInvestigator} is contains, case-insensitive, against
 * {@code principal_investigator_name}.
 *
 * <p>No status filter: the only status this search returns is
 * {@code proposal_sequence_status} (a version-lifecycle flag), not the
 * business Proposal status, so offering it as "Status" would mislead.
 */
public record ProposalSearchFilters(
        String sponsor,
        String principalInvestigator,
        String leadUnit
) {

    public static ProposalSearchFilters none() {
        return new ProposalSearchFilters(null, null, null);
    }

    public ProposalSearchFilters {
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
        return sponsor != null || principalInvestigator != null || leadUnit != null;
    }
}
