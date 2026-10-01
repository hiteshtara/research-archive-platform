package edu.bu.archive.adapter.out.persistence;

import java.util.List;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/*
 * A new, separate repository - deliberately NOT a new method on
 * SemanticSearchRepository, whose sole job (per its own class comment
 * and the guard added alongside V071) is excluding evidence rows from
 * Global Search. Modifying that file would touch a dirty, uncommitted,
 * already-tested file this Phase 3 design explicitly leaves alone. See
 * docs/architecture/AWARD_EVIDENCE_RETRIEVAL_PHASE3_DESIGN.md section 4.
 *
 * Reads archive.search_embedding (V071) only, always scoped to exactly
 * one award_number via parent_business_identifier - the same scoping
 * build_evidence_embedding.py itself uses when writing these rows.
 * document_type IN (:documentTypes) mirrors SemanticSearchRepository's
 * own proven IN-list binding convention (a Collection parameter bound
 * to IN (:name), not Postgres's native = ANY(:name) array syntax).
 *
 * The similarity threshold is applied in an outer WHERE over a CTE
 * (distance can't be referenced by its own alias in the same SELECT's
 * WHERE clause in Postgres) so both the threshold and the LIMIT apply
 * to the correctly-scoped, correctly-typed candidate set only - never
 * a threshold-then-limit-across-everything that could leak an
 * over-threshold row from a different Award or type into the count.
 *
 * No JOIN anywhere in this query - every row of
 * archive.search_embedding is already unique per
 * (module, document_type, exact_record_id) via V071's own unique
 * index, so no row can appear twice in one result set by construction;
 * no application-level deduplication pass is needed.
 */
@Repository
@Transactional(readOnly = true)
public class AwardEvidenceRetrievalRepository {

    private final JdbcClient jdbcClient;

    public AwardEvidenceRetrievalRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    public List<AwardEvidenceRow> findNearestEvidence(
            String awardNumber,
            List<String> documentTypes,
            float[] queryEmbedding,
            double maxDistance,
            int topK
    ) {
        String vectorLiteral = toVectorLiteral(queryEmbedding);

        return jdbcClient.sql("""
                WITH candidates AS (
                    SELECT document_type,
                           parent_business_identifier AS award_number,
                           source_text, source_table, source_primary_key,
                           embedding <=> CAST(:queryEmbedding AS vector) AS distance
                    FROM archive.search_embedding
                    WHERE module = 'AWARD'
                      AND parent_business_identifier = :awardNumber
                      AND document_type IN (:documentTypes)
                )
                SELECT document_type, award_number, source_text, source_table,
                       source_primary_key, distance
                FROM candidates
                WHERE distance <= :maxDistance
                ORDER BY distance, source_primary_key
                LIMIT :topK
                """)
                .param("queryEmbedding", vectorLiteral)
                .param("awardNumber", awardNumber)
                .param("documentTypes", documentTypes)
                .param("maxDistance", maxDistance)
                .param("topK", topK)
                .query((resultSet, rowNumber) ->
                        new AwardEvidenceRow(
                                resultSet.getString("document_type"),
                                resultSet.getString("award_number"),
                                resultSet.getString("source_text"),
                                resultSet.getString("source_table"),
                                resultSet.getLong("source_primary_key"),
                                resultSet.getDouble("distance")
                        )
                )
                .list();
    }

    /** One archive.award_funding_proposal link: the Award version it belongs to and the linked Proposal. */
    public record FundingProposalLink(Long awardId, String proposalNumber) {
    }

    /*
     * Record authorization for RELATED_PROPOSAL evidence: the evidence row
     * is keyed by award_funding_proposal_id (etl/build_evidence_embedding.py),
     * so the linked Proposal and the owning Award version are looked up here
     * to decide whether the caller may see the related record.
     */
    public java.util.Map<Long, FundingProposalLink> findFundingProposalLinks(
            java.util.Collection<Long> awardFundingProposalIds
    ) {
        java.util.Map<Long, FundingProposalLink> links = new java.util.HashMap<>();
        if (awardFundingProposalIds.isEmpty()) {
            return links;
        }
        jdbcClient.sql("""
                SELECT DISTINCT afp.award_funding_proposal_id, afp.award_id, pv.proposal_number
                FROM archive.award_funding_proposal afp
                JOIN archive.proposal_version pv ON pv.proposal_id = afp.proposal_id
                WHERE afp.award_funding_proposal_id IN (:ids)
                """)
                .param("ids", java.util.List.copyOf(awardFundingProposalIds))
                .query((rs, n) -> {
                    long id = rs.getLong("award_funding_proposal_id");
                    long awardId = rs.getLong("award_id");
                    Long owningAward = rs.wasNull() ? null : awardId;
                    links.put(id, new FundingProposalLink(owningAward, rs.getString("proposal_number")));
                    return null;
                })
                .list();
        return links;
    }

    private static String toVectorLiteral(float[] embedding) {
        StringBuilder builder = new StringBuilder(embedding.length * 10);
        builder.append('[');
        for (int i = 0; i < embedding.length; i++) {
            if (i > 0) {
                builder.append(',');
            }
            builder.append(embedding[i]);
        }
        builder.append(']');
        return builder.toString();
    }
}
