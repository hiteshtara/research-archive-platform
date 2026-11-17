package edu.bu.archive.adapter.out.persistence;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import edu.bu.archive.application.authorization.SqlFragment;

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
 * QA H5 (2026-11-17): for a restricted caller the CTE also carries a
 * per-related-type EXISTS authorization predicate (see the scoped
 * overload below). The outer query still has no JOIN - each EXISTS is a
 * correlated semi-join that can only remove rows, never duplicate them.
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
        return findNearestEvidence(awardNumber, documentTypes, queryEmbedding,
                maxDistance, topK, RelatedEvidenceScope.UNRESTRICTED);
    }

    /**
     * QA finding H5 (2026-11-17): the RELATED_PROPOSAL, RELATED_NEGOTIATION
     * and RELATED_SUBAWARD rows describe ANOTHER module's record (proposal
     * number and title; negotiator, agreement type and status; subaward code,
     * document and status - see etl/build_evidence_embedding.py). Gating only
     * the parent Award handed those excerpts to callers who could not open
     * the related record. Each such row must now pass its SOURCE module's
     * own rule, decided on the exact record the evidence row was built from.
     *
     * <p>The predicate sits in the candidates CTE's WHERE, so the distance
     * threshold, ORDER BY and LIMIT :topK all see only authorized rows - an
     * unauthorized row can neither occupy a top-K slot nor change the
     * result count (and therefore insufficientEvidence).
     *
     * <p>The related record is identified by {@code exact_record_id}, which
     * for these three types is the raw primary key of the source row written
     * by build_evidence_embedding.py (only AWARD_TERM_REPORT ids are
     * negated) and is checked together with {@code source_table}:
     * <ul>
     *   <li>RELATED_PROPOSAL: archive.award_funding_proposal
     *       .award_funding_proposal_id; the PROPOSAL rule is decided on the
     *       EXACT linked proposal_version (afp.proposal_id) - the version
     *       whose number and title the excerpt quotes, and the same version
     *       the legacy /api/awards/{n}/proposals list decides (B1). A link to
     *       a Proposal not yet archived cannot satisfy it (fails closed).</li>
     *   <li>RELATED_NEGOTIATION: archive.negotiation.negotiation_id; the
     *       NEGOTIATION hybrid rule on that row, exactly as the Award's
     *       associated-negotiations list decides it (B1).</li>
     *   <li>RELATED_SUBAWARD: archive.subaward_funding
     *       .subaward_funding_source_id, joined to archive.subaward on
     *       subaward_id (never the denormalised funding.subaward_code); the
     *       family-level SUBAWARD rule, as the funding-subawards list (B1).</li>
     * </ul>
     *
     * <p>{@link RelatedEvidenceScope#UNRESTRICTED} (enforcement off, Central)
     * renders the original statement text unchanged.
     */
    public List<AwardEvidenceRow> findNearestEvidence(
            String awardNumber,
            List<String> documentTypes,
            float[] queryEmbedding,
            double maxDistance,
            int topK,
            RelatedEvidenceScope relatedScope
    ) {
        String vectorLiteral = toVectorLiteral(queryEmbedding);
        RelatedEvidenceScope scope = relatedScope == null
                ? RelatedEvidenceScope.DENY_ALL
                : relatedScope;
        SqlFragment related = relatedPredicate(scope);

        JdbcClient.StatementSpec statement = jdbcClient.sql("""
                WITH candidates AS (
                    SELECT document_type,
                           parent_business_identifier AS award_number,
                           source_text, source_table, source_primary_key,
                           embedding <=> CAST(:queryEmbedding AS vector) AS distance
                    FROM archive.search_embedding
                    WHERE module = 'AWARD'
                      AND parent_business_identifier = :awardNumber
                      AND document_type IN (:documentTypes)
                """ + related.sql() + """
                )
                SELECT document_type, award_number, source_text, source_table,
                       source_primary_key, distance
                FROM candidates
                WHERE distance <= :maxDistance
                ORDER BY distance, source_primary_key
                LIMIT :topK
                """);
        for (Map.Entry<String, Object> parameter : related.params().entrySet()) {
            statement = statement.param(parameter.getKey(), parameter.getValue());
        }

        return statement
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

    /**
     * One OR-branch per related type, plus a pass-through for the Award's
     * own evidence types (already covered by the parent gate and
     * AwardArchiveService.findFamily's family-completeness rule). Empty when
     * every related scope is NONE, so the unrestricted statement is
     * byte-identical to the pre-H5 one.
     */
    static SqlFragment relatedPredicate(RelatedEvidenceScope scope) {
        if (scope.isUnrestricted()) {
            return SqlFragment.NONE;
        }
        SqlFragment proposal = scope.proposal().withParameterPrefix("ev_p_");
        SqlFragment negotiation = scope.negotiation().withParameterPrefix("ev_n_");
        SqlFragment subaward = scope.subaward().withParameterPrefix("ev_s_");

        String sql = """
                      AND (search_embedding.document_type NOT IN
                               ('RELATED_PROPOSAL', 'RELATED_NEGOTIATION', 'RELATED_SUBAWARD')
                           OR (search_embedding.document_type = 'RELATED_PROPOSAL'
                               AND search_embedding.source_table = 'archive.award_funding_proposal'
                               AND EXISTS (SELECT 1
                                   FROM archive.award_funding_proposal ev_afp
                                   JOIN archive.proposal_version ev_proposal
                                       ON ev_proposal.proposal_id = ev_afp.proposal_id
                                   WHERE ev_afp.award_funding_proposal_id
                                       = search_embedding.exact_record_id
                """ + proposal.sql() + """
                               ))
                           OR (search_embedding.document_type = 'RELATED_NEGOTIATION'
                               AND search_embedding.source_table = 'archive.negotiation'
                               AND EXISTS (SELECT 1
                                   FROM archive.negotiation ev_negotiation
                                   WHERE ev_negotiation.negotiation_id
                                       = search_embedding.exact_record_id
                """ + negotiation.sql() + """
                               ))
                           OR (search_embedding.document_type = 'RELATED_SUBAWARD'
                               AND search_embedding.source_table = 'archive.subaward_funding'
                               AND EXISTS (SELECT 1
                                   FROM archive.subaward_funding ev_funding
                                   JOIN archive.subaward ev_subaward
                                       ON ev_subaward.subaward_id = ev_funding.subaward_id
                                   WHERE ev_funding.subaward_funding_source_id
                                       = search_embedding.exact_record_id
                """ + subaward.sql() + """
                               )))
                """;
        Map<String, Object> params = new LinkedHashMap<>();
        params.putAll(proposal.params());
        params.putAll(negotiation.params());
        params.putAll(subaward.params());
        return new SqlFragment(sql, params);
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
