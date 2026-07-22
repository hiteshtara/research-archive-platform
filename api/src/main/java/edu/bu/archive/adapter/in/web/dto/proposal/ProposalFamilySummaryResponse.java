package edu.bu.archive.adapter.in.web.dto.proposal;

public record ProposalFamilySummaryResponse(
        String proposalNumber,
        String title,
        String status,
        String sponsorName,
        String leadUnitName,
        String principalInvestigator,
        Integer latestVersionNumber,
        Long currentProposalId
) {
}
