package edu.bu.archive.adapter.in.web.dto.ai;

import edu.bu.archive.domain.model.ai.AwardAiCurrentRecord;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public record AwardAiCurrentRecordResponse(
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
    public AwardAiCurrentRecordResponse {
        principalInvestigators =
                List.copyOf(principalInvestigators);
    }

    public static AwardAiCurrentRecordResponse from(
            AwardAiCurrentRecord record
    ) {
        return new AwardAiCurrentRecordResponse(
                record.awardId(),
                record.awardNumber(),
                record.sequenceNumber(),
                record.title(),
                record.status(),
                record.sponsor(),
                record.leadUnit(),
                record.principalInvestigators(),
                record.beginDate(),
                record.closeoutDate(),
                record.anticipatedTotalAmount(),
                record.obligatedTotalAmount()
        );
    }
}
