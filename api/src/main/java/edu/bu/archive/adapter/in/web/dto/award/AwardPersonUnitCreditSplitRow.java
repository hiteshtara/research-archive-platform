package edu.bu.archive.adapter.in.web.dto.award;

import java.math.BigDecimal;

public record AwardPersonUnitCreditSplitRow(
        Long awardPersonUnitId,
        String invCreditTypeCode,
        BigDecimal credit
) {
}
