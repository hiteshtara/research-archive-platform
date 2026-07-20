package edu.bu.archive.adapter.in.web.dto.award;

import java.math.BigDecimal;

public record AwardAmountResponse(
        Long awardAmountInfoId,
        Long awardId,
        String awardNumber,
        Integer sequenceNumber,
        BigDecimal anticipatedChangeDirect,
        BigDecimal anticipatedChangeIndirect,
        BigDecimal anticipatedTotalDirect,
        BigDecimal anticipatedTotalIndirect,
        BigDecimal obligatedTotalDirect,
        BigDecimal obligatedTotalIndirect,
        BigDecimal anticipatedTotalAmount,
        BigDecimal obligatedTotalAmount,
        String tnmDocumentNumber,
        Long sourceVersionNumber
) {
}
