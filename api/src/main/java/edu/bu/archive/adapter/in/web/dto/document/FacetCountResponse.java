package edu.bu.archive.adapter.in.web.dto.document;

// One facet bucket (e.g. module="AWARD", count=49827) computed against
// the same filtered result set as the paginated results themselves -
// never a separately cached/denormalized count, per
// docs/architecture/KUALI_DOCUMENT_EXPLORER_DESIGN.md §10.
public record FacetCountResponse(
        String value,
        long count
) {
}
