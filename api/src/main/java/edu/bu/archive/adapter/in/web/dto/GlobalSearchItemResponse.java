package edu.bu.archive.adapter.in.web.dto;

/*
 * The one stable, cross-domain result shape Global Search returns
 * regardless of which domain a result came from - see
 * GlobalSearchService, which is the only place that constructs these.
 * Every domain-specific identifier is nullable (protocolId is IRB-only,
 * awardId/sequenceNumber/primaryCurrent are Award-only) so the frontend
 * never has to guess which fields apply to which module.
 *
 * route is the backend-computed, ready-to-navigate frontend path (e.g.
 * "/awards/3831872" or "/irb/history/42") - null when this result has
 * no real place to click through to, so the frontend never has to
 * reconstruct a route from module-specific identifiers itself. A card
 * with a null route must render as non-clickable.
 *
 * matchType is null for every structured (exact/lexical) result and is
 * only ever "RELATED" for a semantic-search result that survived
 * deduplication against the structured branches - see
 * GlobalSearchService's semantic-search integration. It never carries a
 * raw similarity score, distance, embedding, or model name - none of
 * those leave the backend.
 *
 * principalInvestigator is populated for AWARD and PROPOSAL results
 * only (structured and semantic alike) - every other domain leaves it
 * null rather than repurposing a differently-meant field (e.g.
 * Negotiation's negotiator, Subaward's organization) as a PI stand-in.
 */
public record GlobalSearchItemResponse(
        String module,
        Long recordId,
        String identifier,
        String title,
        String subtitle,
        String status,
        String documentNumber,
        String matchedField,
        String matchedValue,
        String route,
        Long protocolId,
        Long awardId,
        Integer sequenceNumber,
        Boolean primaryCurrent,
        String matchType,
        String principalInvestigator
) {
}
