package edu.bu.archive.adapter.in.web.dto;

import java.util.List;

/*
 * failedModules names every domain whose search errored during this
 * request (e.g. "IRB", "AWARD") - GlobalSearchService always returns
 * whatever domains DID succeed rather than failing the whole request
 * for one domain's outage. Empty when every domain searched cleanly.
 *
 * totalResults is the number of rows in `results`, NOT the number of
 * records that matched. Every module is capped at 25, so the two differ
 * for any broad query. QA TC-040 reported this as misleading and it is:
 * the name promises a total and delivers a page size. The value is left
 * unchanged for compatibility, and moduleTotals now carries what a
 * reader actually needs - per module, how many came back, how many
 * matched where that is known, and whether the cap was hit.
 */
public record GlobalSearchResponse(
        String query,
        long totalResults,
        List<GlobalSearchItemResponse> results,
        List<String> failedModules,
        List<GlobalSearchModuleTotal> moduleTotals
) {

    /**
     * Back-compatible form, before per-module totals existed.
     *
     * <p>Retained so a caller that does not care about totals is unaffected.
     * It reports no totals rather than inventing empty ones.
     */
    public GlobalSearchResponse(
            String query,
            long totalResults,
            List<GlobalSearchItemResponse> results,
            List<String> failedModules
    ) {
        this(query, totalResults, results, failedModules, List.of());
    }
}
