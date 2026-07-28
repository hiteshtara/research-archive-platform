package edu.bu.archive.domain.model.ai;

import java.time.LocalDate;

public record AwardAiTimelineRecord(
        Long awardId,
        String awardNumber,
        Integer sequenceNumber,
        Boolean current,
        Boolean primaryCurrent,
        String status,
        String awardSequenceStatus,
        String sponsor,
        String leadUnit,
        LocalDate beginDate,
        LocalDate closeoutDate
) {
}
