package edu.bu.archive.adapter.in.web.dto.award;

import java.util.List;

public record AwardFamilyResponse(
        String awardNumber,
        AwardRowResponse current,
        List<AwardSequenceResponse> sequences
) {
}
