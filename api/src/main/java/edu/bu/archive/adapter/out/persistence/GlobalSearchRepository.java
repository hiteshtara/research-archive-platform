package edu.bu.archive.adapter.out.persistence;

import edu.bu.archive.adapter.in.web.dto.GlobalSearchItemResponse;
import edu.bu.archive.adapter.in.web.dto.GlobalSearchResponse;

import java.util.List;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
@Transactional(readOnly = true)
public class GlobalSearchRepository {

    private static final int RESULT_LIMIT = 50;

    private final JdbcClient jdbcClient;

    public GlobalSearchRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    public GlobalSearchResponse search(String query) {
        String normalizedQuery = query.trim();
        String searchPattern = "%" + escapeLike(normalizedQuery) + "%";

        long totalResults = jdbcClient.sql("""
                SELECT COUNT(*)
                FROM archive.v_global_search
                WHERE search_text ILIKE :searchPattern ESCAPE '\\'
                """)
                .param("searchPattern", searchPattern)
                .query(Long.class)
                .single();

        List<GlobalSearchItemResponse> results = jdbcClient.sql("""
                SELECT
                    record_id,
                    protocol_id,
                    module,
                    COALESCE(
                        NULLIF(study_id, ''),
                        protocol_base
                    ) AS identifier,
                    protocol_number AS secondary_identifier,
                    COALESCE(
                        NULLIF(title, ''),
                        'Untitled IRB protocol'
                    ) AS title,
                    protocol_status AS status,
                    pi_full_name AS person_name,
                    protocol_type AS record_type,

                    CASE
                        WHEN document_numbers ILIKE :searchPattern ESCAPE '\\'
                            THEN 1
                        WHEN crc_protocol_numbers ILIKE :searchPattern ESCAPE '\\'
                            THEN 2
                        WHEN protocol_number ILIKE :searchPattern ESCAPE '\\'
                            THEN 3
                        WHEN protocol_base ILIKE :searchPattern ESCAPE '\\'
                            THEN 4
                        WHEN study_id ILIKE :searchPattern ESCAPE '\\'
                            THEN 5
                        WHEN funding_sources ILIKE :searchPattern ESCAPE '\\'
                            THEN 6
                        WHEN title ILIKE :searchPattern ESCAPE '\\'
                            THEN 7
                        WHEN pi_full_name ILIKE :searchPattern ESCAPE '\\'
                            THEN 8
                        ELSE 20
                    END AS search_rank
                FROM archive.v_global_search
                WHERE search_text ILIKE :searchPattern ESCAPE '\\'
                ORDER BY
                    search_rank,
                    title NULLS LAST,
                    record_id
                LIMIT :resultLimit
                """)
                .param("searchPattern", searchPattern)
                .param("resultLimit", RESULT_LIMIT)
                .query((resultSet, rowNumber) ->
                        new GlobalSearchItemResponse(
                                resultSet.getObject("record_id", Long.class),
                                resultSet.getObject("protocol_id", Long.class),
                                resultSet.getString("module"),
                                resultSet.getString("identifier"),
                                resultSet.getString("secondary_identifier"),
                                resultSet.getString("title"),
                                resultSet.getString("status"),
                                resultSet.getString("person_name"),
                                resultSet.getString("record_type")
                        )
                )
                .list();

        return new GlobalSearchResponse(
                normalizedQuery,
                totalResults,
                results
        );
    }

    private static String escapeLike(String value) {
        return value
                .replace("\\", "\\\\")
                .replace("%", "\\%")
                .replace("_", "\\_");
    }
}
