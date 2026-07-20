package edu.bu.archive.adapter.in.web.dto.award;

import java.time.LocalDate;

public record AwardHistoryResponse(

        Long awardId,

        String awardNumber,

        Integer sequenceNumber,

        String title,

        String status,

        String awardSequenceStatus,

        String sponsor,

        String primeSponsor,

        String leadUnit,

        String accountNumber,

        String sponsorAwardNumber,

        LocalDate beginDate,

        LocalDate closeoutDate,

        Boolean current,

        Boolean primaryCurrent

) {
}
