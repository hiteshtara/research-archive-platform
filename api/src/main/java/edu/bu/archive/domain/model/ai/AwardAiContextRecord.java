package edu.bu.archive.domain.model.ai;

import java.time.LocalDate;

public record AwardAiContextRecord(
        Long awardId,
        String awardNumber,
        Integer sequenceNumber,
        Boolean current,
        Boolean primaryCurrent,
        String title,
        String status,
        String awardSequenceStatus,
        String sponsor,
        String primeSponsor,
        String leadUnit,
        LocalDate beginDate,
        LocalDate closeoutDate
) {
}
