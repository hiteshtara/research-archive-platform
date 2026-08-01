package edu.bu.archive.adapter.in.web.dto.award;

/*
 * One raw archive.award_hierarchy row - internal to
 * AwardArchiveRepository/AwardArchiveService's tree-building, never
 * returned directly from a controller. active is the raw 'Y'/'N' text
 * flag exactly as archived (see AWARD_TIME_AND_MONEY_DESIGN.md) - never
 * coerced at the repository layer, so the service can decide how to
 * expose it.
 */
public record AwardHierarchyEdgeRow(
        String rootAwardNumber,
        String awardNumber,
        String parentAwardNumber,
        String active
) {
}
