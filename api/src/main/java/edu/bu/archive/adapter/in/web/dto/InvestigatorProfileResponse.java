package edu.bu.archive.adapter.in.web.dto;

import java.util.List;

public record InvestigatorProfileResponse(
        String name,
        String email,
        String buid,
        long currentStudyCount,
        long historicalStudyCount,
        List<InvestigatorStudyResponse> currentStudies,
        List<InvestigatorStudyResponse> historicalStudies
) {
}
