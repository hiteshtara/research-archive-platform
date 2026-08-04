package edu.bu.archive.adapter.out.persistence;

import java.util.List;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/*
 * IRB's own search - unchanged ranking/ILIKE logic from before the
 * Global Search fan-out redesign (see GlobalSearchService, which now
 * owns cross-domain merging/ranking that used to live here). This
 * repository only ever touches archive.v_global_search, which is
 * itself IRB-only by construction (see V009/V010) - Award's own search
 * is a separate, independently-reused implementation
 * (AwardArchiveService.search), never folded into this view.
 */
@Repository
@Transactional(readOnly = true)
public class GlobalSearchRepository {

    private final JdbcClient jdbcClient;

    public GlobalSearchRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    public List<IrbGlobalSearchRow> search(String query, int limit) {
        String normalizedQuery = query.trim();
        String searchPattern = "%" + escapeLike(normalizedQuery) + "%";

        return jdbcClient.sql("""
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
                    END AS search_rank,

                    -- Parallels search_rank as a human-readable label,
                    -- purely additive (GlobalSearchService's own
                    -- matchedField on the shared cross-domain
                    -- contract) - the ranking SQL itself is unchanged.
                    CASE
                        WHEN document_numbers ILIKE :searchPattern ESCAPE '\\'
                            THEN 'Document Number'
                        WHEN crc_protocol_numbers ILIKE :searchPattern ESCAPE '\\'
                            THEN 'CRC Protocol Number'
                        WHEN protocol_number ILIKE :searchPattern ESCAPE '\\'
                            THEN 'Protocol Number'
                        WHEN protocol_base ILIKE :searchPattern ESCAPE '\\'
                            THEN 'Protocol Number'
                        WHEN study_id ILIKE :searchPattern ESCAPE '\\'
                            THEN 'Study ID'
                        WHEN funding_sources ILIKE :searchPattern ESCAPE '\\'
                            THEN 'Funding Source'
                        WHEN title ILIKE :searchPattern ESCAPE '\\'
                            THEN 'Title'
                        WHEN pi_full_name ILIKE :searchPattern ESCAPE '\\'
                            THEN 'PI'
                        ELSE 'IRB Record'
                    END AS matched_field,

                    -- Best-effort representative value, not necessarily
                    -- the exact matching substring: several source
                    -- columns (document_numbers, crc_protocol_numbers)
                    -- are "|"-aggregated across every historical
                    -- version, so isolating the one value that matched
                    -- would need per-value scanning this view doesn't
                    -- do. Falls back to a stable, always-present field.
                    CASE
                        WHEN document_numbers ILIKE :searchPattern ESCAPE '\\'
                            THEN document_numbers
                        WHEN crc_protocol_numbers ILIKE :searchPattern ESCAPE '\\'
                            THEN crc_protocol_numbers
                        WHEN protocol_number ILIKE :searchPattern ESCAPE '\\'
                            THEN protocol_number
                        WHEN protocol_base ILIKE :searchPattern ESCAPE '\\'
                            THEN protocol_base
                        WHEN study_id ILIKE :searchPattern ESCAPE '\\'
                            THEN study_id
                        WHEN funding_sources ILIKE :searchPattern ESCAPE '\\'
                            THEN funding_sources
                        WHEN title ILIKE :searchPattern ESCAPE '\\'
                            THEN title
                        WHEN pi_full_name ILIKE :searchPattern ESCAPE '\\'
                            THEN pi_full_name
                        ELSE COALESCE(NULLIF(study_id, ''), protocol_base)
                    END AS matched_value
                FROM archive.v_global_search
                WHERE search_text ILIKE :searchPattern ESCAPE '\\'
                ORDER BY
                    search_rank,
                    title NULLS LAST,
                    record_id
                LIMIT :resultLimit
                """)
                .param("searchPattern", searchPattern)
                .param("resultLimit", limit)
                .query((resultSet, rowNumber) ->
                        new IrbGlobalSearchRow(
                                resultSet.getObject("record_id", Long.class),
                                resultSet.getObject("protocol_id", Long.class),
                                resultSet.getString("module"),
                                resultSet.getString("identifier"),
                                resultSet.getString("secondary_identifier"),
                                resultSet.getString("title"),
                                resultSet.getString("status"),
                                resultSet.getString("person_name"),
                                resultSet.getString("record_type"),
                                resultSet.getInt("search_rank"),
                                resultSet.getString("matched_field"),
                                resultSet.getString("matched_value")
                        )
                )
                .list();
    }

    private static String escapeLike(String value) {
        return value
                .replace("\\", "\\\\")
                .replace("%", "\\%")
                .replace("_", "\\_");
    }
}
