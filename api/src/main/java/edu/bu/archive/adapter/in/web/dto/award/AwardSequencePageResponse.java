package edu.bu.archive.adapter.in.web.dto.award;

import java.util.List;

public record AwardSequencePageResponse(
        List<AwardSequenceSummaryResponse> content,
        int page,
        int size,
        long totalElements,
        int totalPages,
        boolean first,
        boolean last
) {
}
