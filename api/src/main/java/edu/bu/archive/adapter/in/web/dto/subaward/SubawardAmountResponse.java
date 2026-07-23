package edu.bu.archive.adapter.in.web.dto.subaward;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

public record SubawardAmountResponse(
        Long subawardAmountInfoId,
        Long subawardId,
        String subawardCode,
        Integer sequenceNumber,
        BigDecimal obligatedAmount,
        BigDecimal obligatedChange,
        BigDecimal obligatedChangeDirect,
        BigDecimal obligatedChangeIndirect,
        BigDecimal anticipatedAmount,
        BigDecimal anticipatedChange,
        BigDecimal anticipatedChangeDirect,
        BigDecimal anticipatedChangeIndirect,
        BigDecimal rate,
        LocalDate effectiveDate,
        LocalDate modificationEffectiveDate,
        String modificationNumber,
        String modificationTypeCode,
        String modificationTypeDescription,
        LocalDate performanceStartDate,
        LocalDate performanceEndDate,
        String purchaseOrderNum,
        String comments,
        String fileDataId,
        String fileName,
        String mimeType,
        LocalDateTime sourceUpdateTimestamp,
        String sourceUpdateUser,
        Long sourceVersionNumber,
        String sourceObjectId
) {
}
