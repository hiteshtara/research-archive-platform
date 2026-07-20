package edu.bu.archive.adapter.in.web.dto.award;

public record AwardFamilySummaryResponse(
        String awardNumber,
        String title,
        String status,
        String awardSequenceStatus,
        String sponsor,
        String leadUnit,
        String accountNumber,
        Integer latestSequenceNumber,
        Long primaryAwardId
) {
}
