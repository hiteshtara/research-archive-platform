package edu.bu.archive.domain.model.ai;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public record AwardAiCurrentRecord(
        Long awardId,
        String awardNumber,
        Integer sequenceNumber,
        String title,
        String status,
        String sponsor,
        String leadUnit,
        List<String> principalInvestigators,
        LocalDate beginDate,
        LocalDate closeoutDate,
        BigDecimal anticipatedTotalAmount,
        BigDecimal obligatedTotalAmount
) {
    public AwardAiCurrentRecord {
        principalInvestigators =
                List.copyOf(principalInvestigators);
    }
}
