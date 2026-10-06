package edu.bu.archive.adapter.out.persistence;

/*
 * The display fields a Subaward semantic result needs, keyed by
 * subaward_code - the value build_search_embedding.py stores as
 * business_number for this module (QA TC-041).
 */
public record SubawardSemanticSummaryRow(
        String subawardCode,
        String title,
        String status,
        String sponsor
) {
}
