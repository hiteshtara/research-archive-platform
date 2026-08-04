package edu.bu.archive.adapter.out.persistence;

/*
 * IRB's own raw search-view row - kept as a distinct internal type from
 * the cross-domain GlobalSearchItemResponse contract
 * (edu.bu.archive.adapter.in.web.dto.GlobalSearchItemResponse), which
 * GlobalSearchService maps this into. IRB's own SQL ranking/ILIKE logic
 * in GlobalSearchRepository is unchanged; matchedField is an additive
 * column using the exact same CASE structure the existing search_rank
 * column already computes, just spelled out as a human-readable label
 * instead of a bare integer.
 */
public record IrbGlobalSearchRow(
        Long recordId,
        Long protocolId,
        String module,
        String identifier,
        String secondaryIdentifier,
        String title,
        String status,
        String personName,
        String recordType,
        int searchRank,
        String matchedField,
        String matchedValue
) {
}
