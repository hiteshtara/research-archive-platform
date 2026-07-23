package edu.bu.archive.adapter.in.web.dto.negotiation;

import java.util.List;

public record NegotiationPageResponse(
        List<NegotiationSummaryResponse> content,
        int page,
        int size,
        long totalElements,
        int totalPages,
        boolean first,
        boolean last
) {
}
