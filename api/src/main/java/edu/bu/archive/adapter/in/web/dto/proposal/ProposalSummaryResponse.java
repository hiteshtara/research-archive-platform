package edu.bu.archive.adapter.in.web.dto.proposal;

import java.math.BigDecimal;
import java.time.LocalDate;

/*
 * proposalId, proposalNumber, sequenceNumber, and workflowDocumentNumber
 * are four distinct identifiers - never inferred one from another (see
 * docs/kuali-business-rules/InstitutionalProposal.md). status is the
 * resolved business status (archive.proposal_version.status_description,
 * e.g. "Funded") - the same convention AwardSummaryResponse.status
 * uses for Award's own status_description. proposalSequenceStatus is
 * the separate version-lifecycle state (ACTIVE/ARCHIVED/CANCELED/
 * PENDING) - "current" for a family means PROPOSAL_SEQUENCE_STATUS =
 * 'ACTIVE', never the highest sequence number.
 */
public record ProposalSummaryResponse(
        Long proposalId,
        String proposalNumber,
        Integer sequenceNumber,
        String workflowDocumentNumber,
        String title,
        String status,
        String proposalSequenceStatus,
        String proposalType,
        String activityType,
        String leadUnitNumber,
        String leadUnitName,
        String sponsorCode,
        String sponsorName,
        String principalInvestigatorId,
        String principalInvestigatorName,
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
