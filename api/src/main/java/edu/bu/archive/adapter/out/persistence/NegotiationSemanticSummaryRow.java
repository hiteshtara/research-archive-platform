package edu.bu.archive.adapter.out.persistence;

/*
 * The display fields a Negotiation semantic result needs, keyed by the
 * value its embedding was stored under (QA TC-041).
 *
 * documentNumber, not negotiationId: build_search_embedding.py stores
 * "document_number AS business_number" for this module, so the document
 * number is what a semantic row carries and therefore what enrichment
 * has to look up by.
 *
 * title comes from archive.negotiation_search_attribute - the
 * Negotiation table itself has no title column, which is why these rows
 * previously rendered with the document number in the title's place.
 */
public record NegotiationSemanticSummaryRow(
        String documentNumber,
        String title,
        String status,
        String negotiator
) {
}
