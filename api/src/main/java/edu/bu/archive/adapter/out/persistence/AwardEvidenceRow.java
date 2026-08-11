package edu.bu.archive.adapter.out.persistence;

/*
 * One archive.search_embedding (V071) evidence row, as returned by
 * AwardEvidenceRetrievalRepository.findNearestEvidence(). Every field
 * is a real V071 column value except distance, which is computed at
 * query time from the same embedding <=> :queryEmbedding expression
 * SemanticSearchRepository already uses - never persisted.
 */
public record AwardEvidenceRow(
        String documentType,
        String awardNumber,
        String sourceText,
        String sourceTable,
        long sourcePrimaryKey,
        double distance
) {
}
