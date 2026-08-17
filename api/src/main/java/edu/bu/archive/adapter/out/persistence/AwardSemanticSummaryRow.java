package edu.bu.archive.adapter.out.persistence;

/*
 * Set-based enrichment projection for Global Search semantic Award
 * results only - see AwardArchiveRepository.findCurrentSummariesForNumbers
 * and GlobalSearchService's semantic-search integration. Deliberately
 * narrow (no account_number, no internal award_id, no embedding/
 * distance) - this exists only to answer "what does a leadership user
 * need to see on a semantic result card", not to be a general Award
 * projection.
 */
public record AwardSemanticSummaryRow(
        String awardNumber,
        String title,
        String status,
        String sponsor,
        String principalInvestigator
) {
}
