package edu.bu.archive.adapter.in.web.dto.ai;

import edu.bu.archive.domain.model.ai.AwardAiTimelineRecord;

import java.time.LocalDate;

public record AwardAiTimelineRecordResponse(
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
    public static AwardAiTimelineRecordResponse from(
            AwardAiTimelineRecord record
    ) {
        return new AwardAiTimelineRecordResponse(
                record.awardId(),
                record.awardNumber(),
                record.sequenceNumber(),
                record.current(),
                record.primaryCurrent(),
                record.status(),
                record.awardSequenceStatus(),
                record.sponsor(),
                record.leadUnit(),
                record.beginDate(),
                record.closeoutDate()
        );
    }
}
