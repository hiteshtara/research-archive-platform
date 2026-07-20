package edu.bu.archive.adapter.in.web.dto.award;

import java.util.List;

public record AwardSequenceDetailResponse(
        String awardNumber,
        Integer sequenceNumber,
        Boolean currentSequence,
        List<AwardRowResponse> rows
) {
}
