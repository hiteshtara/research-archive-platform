package edu.bu.archive.adapter.in.web.dto.subaward;

import java.util.List;

public record SubawardPageResponse(
        List<SubawardSummaryResponse> content,
        int page,
        int size,
        long totalElements,
        int totalPages,
        boolean first,
        boolean last
) {
}
