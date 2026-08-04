package edu.bu.archive.adapter.in.web.dto.proposal;

import java.time.LocalDateTime;

public record ProposalVersionSummaryResponse(
        Long proposalId,
        String proposalNumber,
        Integer sequenceNumber,
        String workflowDocumentNumber,
        String proposalSequenceStatus,
        String status,
        String title,
        LocalDateTime sourceUpdateTimestamp
) {
}
