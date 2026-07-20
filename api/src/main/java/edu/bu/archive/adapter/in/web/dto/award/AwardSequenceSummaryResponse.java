package edu.bu.archive.adapter.in.web.dto.award;

public record AwardSequenceSummaryResponse(
        Integer sequenceNumber,
        String status,
        String awardSequenceStatus,
        Boolean currentSequence,
        Long rowCount,
        Long representativeAwardId
) {
}
