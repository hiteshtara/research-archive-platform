package edu.bu.archive.adapter.in.web.dto.award;

import edu.bu.archive.adapter.in.web.dto.PageResponse;

/*
 * Wraps the existing family-level search results (unchanged behavior -
 * see AwardArchiveRepository.searchAwards) with an additive
 * exactDocumentMatch: non-null only when the query is an exact match for
 * some archived Award version's real workflow document number, in which
 * case it identifies that SPECIFIC version (awardId + sequenceNumber),
 * not merely the Award family's current version. A client should treat
 * exactDocumentMatch as the top-ranked result when present - it isn't
 * duplicated into results.content.
 */
public record AwardSearchResponse(
        AwardDocumentNumberMatchResponse exactDocumentMatch,
        PageResponse<AwardSearchResultResponse> results
) {
}
