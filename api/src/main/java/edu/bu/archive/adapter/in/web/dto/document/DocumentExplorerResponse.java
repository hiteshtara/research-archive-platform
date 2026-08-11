package edu.bu.archive.adapter.in.web.dto.document;

import edu.bu.archive.adapter.in.web.dto.PageResponse;

import java.util.List;

// results are the paginated, filtered rows; moduleFacets are counts by
// module computed against the SAME filter set (so applying a status
// filter updates the module counts too) - see design doc §10.
public record DocumentExplorerResponse(
        PageResponse<DocumentExplorerResultResponse> results,
        List<FacetCountResponse> moduleFacets
) {
}
