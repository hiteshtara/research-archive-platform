package edu.bu.archive.adapter.in.web.dto.award;

import java.time.LocalDateTime;

public record AwardProposalResponse(
        Long awardFundingProposalId,
        Long awardId,
        Long proposalId,
        String activeFlag,
        LocalDateTime sourceUpdateTimestamp,
        String sourceUpdateUser,
        Long sourceVersionNumber
) {
}
