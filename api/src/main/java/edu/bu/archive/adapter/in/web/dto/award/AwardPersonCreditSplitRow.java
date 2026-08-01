package edu.bu.archive.adapter.in.web.dto.award;

import java.math.BigDecimal;

public record AwardPersonCreditSplitRow(
        Long awardPersonId,
        String invCreditTypeCode,
        BigDecimal credit
) {
}
