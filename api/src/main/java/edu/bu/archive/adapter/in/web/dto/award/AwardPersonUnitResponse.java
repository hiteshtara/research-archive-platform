package edu.bu.archive.adapter.in.web.dto.award;

import java.util.List;

public record AwardPersonUnitResponse(
        String unitNumber,
        boolean leadUnit,
        List<AwardCreditSplitResponse> creditSplits
) {
}
