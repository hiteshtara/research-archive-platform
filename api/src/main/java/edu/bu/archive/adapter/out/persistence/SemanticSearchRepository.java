package edu.bu.archive.adapter.out.persistence;

import java.util.List;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/*
 * Reads archive.search_embedding (V070) only - never
 * archive.search_embedding_poc, which stays a permanently separate,
 * PoC-only table (see V069's comment and GlobalSearchService's own
 * documentation of this decision). Cosine distance via pgvector's <=>
 * operator, same as etl/query_search_embedding_poc.py's already-proven
 * query shape. No similarity threshold - the threshold experiment found
 * no single global cutoff works; a hard LIMIT (Top-5 max, enforced again
 * in GlobalSearchService regardless of what's requested here) is the
 * only cap.
 */
@Repository
@Transactional(readOnly = true)
public class SemanticSearchRepository {

    private final JdbcClient jdbcClient;

    public SemanticSearchRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    public List<SemanticSearchRow> findNearest(float[] queryEmbedding, int topK) {
        String vectorLiteral = toVectorLiteral(queryEmbedding);

        return jdbcClient.sql("""
                SELECT module, record_id, canonical_family_id, business_number,
                       embedding <=> CAST(:queryEmbedding AS vector) AS distance
                FROM archive.search_embedding
                ORDER BY distance
                LIMIT :topK
                """)
                .param("queryEmbedding", vectorLiteral)
                .param("topK", topK)
                .query((resultSet, rowNumber) ->
                        new SemanticSearchRow(
                                resultSet.getString("module"),
                                resultSet.getLong("record_id"),
                                resultSet.getLong("canonical_family_id"),
                                resultSet.getString("business_number"),
                                resultSet.getDouble("distance")
                        )
                )
                .list();
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
