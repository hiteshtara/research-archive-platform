package edu.bu.researcharchive.api.proposal.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

public record ProposalRowResponse(
        Long proposalId,
        String proposalNumber,
        Integer versionNumber,
        String title,
        String status,
        String proposalType,
        String activityType,
        String sponsorName,
        String leadUnitNumber,
        String leadUnitName,
        String principalInvestigator,
        LocalDate initialStartDate,
        LocalDate initialEndDate,
        BigDecimal initialDirectCost,
        BigDecimal initialIndirectCost,
        BigDecimal initialTotalCost,
        LocalDate totalStartDate,
        LocalDate totalEndDate,
        BigDecimal totalDirectCost,
        BigDecimal totalIndirectCost,
        BigDecimal totalCost
) {
}
