package edu.bu.archive.adapter.in.web.dto.award;

import java.math.BigDecimal;
import java.util.List;

public record AwardHierarchyNodeResponse(
        String awardNumber,
        Long awardId,
        Integer latestSequenceNumber,
        String parentAwardNumber,
        Boolean active,
        String title,
        String status,
        String principalInvestigator,
        String sponsor,
        String leadUnit,
        BigDecimal currentObligatedAmount,
        List<AwardHierarchyNodeResponse> children
) {
}
