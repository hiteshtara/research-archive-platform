package edu.bu.archive.adapter.out.persistence;

/*
 * Set-based enrichment projection for Global Search semantic Proposal
 * results only - see ProposalArchiveRepository.findCurrentSummariesForNumbers
 * and GlobalSearchService's semantic-search integration. Deliberately
 * narrow, mirroring AwardSemanticSummaryRow's own scope.
 */
public record ProposalSemanticSummaryRow(
        String proposalNumber,
        String title,
        String status,
        String sponsor,
        String principalInvestigator
) {
}
