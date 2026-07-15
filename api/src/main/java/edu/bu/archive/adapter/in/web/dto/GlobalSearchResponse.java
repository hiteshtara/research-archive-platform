package edu.bu.archive.adapter.in.web.dto;

import java.util.List;

public record GlobalSearchResponse(
        String query,
        long totalResults,
        List<GlobalSearchItemResponse> results
) {
}
