package edu.bu.archive.adapter.in.web.dto.award;

import java.util.List;

public record AwardSequenceResponse(
        Integer sequenceNumber,
        Boolean currentSequence,
        List<AwardRowResponse> rows
) {
}
