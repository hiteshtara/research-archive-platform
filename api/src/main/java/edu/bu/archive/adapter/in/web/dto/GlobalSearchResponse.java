package edu.bu.archive.adapter.in.web.dto;

import java.util.List;

/*
 * failedModules names every domain whose search errored during this
 * request (e.g. "IRB", "AWARD") - GlobalSearchService always returns
 * whatever domains DID succeed rather than failing the whole request
 * for one domain's outage. Empty when every domain searched cleanly.
 */
public record GlobalSearchResponse(
        String query,
        long totalResults,
        List<GlobalSearchItemResponse> results,
        List<String> failedModules
) {
}
