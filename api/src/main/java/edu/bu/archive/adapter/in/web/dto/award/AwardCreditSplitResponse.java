package edu.bu.archive.adapter.in.web.dto.award;

import java.math.BigDecimal;

public record AwardCreditSplitResponse(
        String creditTypeCode,
        BigDecimal credit
) {
}
