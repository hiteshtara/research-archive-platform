package edu.bu.archive.adapter.in.web.dto.award;

import java.util.List;

public record AwardTermsResponse(
        List<AwardSponsorTermResponse> sponsorTerms,
        List<AwardReportTermResponse> reportTerms
) {
}
