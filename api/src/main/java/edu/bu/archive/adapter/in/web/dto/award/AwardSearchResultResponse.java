package edu.bu.archive.adapter.in.web.dto.award;

import java.math.BigDecimal;

public record AwardSearchResultResponse(
        Long awardId,
        String awardNumber,
        Integer latestSequenceNumber,
        String title,
        String status,
        String principalInvestigator,
        String sponsor,
        String leadUnit,
        String grantNumber,
        BigDecimal currentObligatedAmount,
        String rootAwardNumber,
        String parentAwardNumber
) {
}
