package edu.bu.archive.adapter.out.persistence;

import edu.bu.archive.adapter.in.web.dto.IrbFamilyResponse;
import edu.bu.archive.adapter.in.web.dto.IrbHistoryResponse;
import edu.bu.archive.adapter.in.web.dto.PageResponse;

import java.util.List;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Repository
@Transactional(readOnly = true)
public class IrbArchiveRepository {

    private final JdbcClient jdbcClient;

    public IrbArchiveRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    public PageResponse<IrbFamilyResponse> findFamilies(
            String query,
            int page,
            int size
    ) {
        String normalized = normalize(query);
        String pattern = "%" + escapeLike(normalized) + "%";
        int offset = page * size;

        long total = jdbcClient.sql("""
                SELECT COUNT(*)
                FROM (
                    SELECT protocol_base
                    FROM archive.irb_protocol_version
                    WHERE :hasQuery = FALSE
                       OR protocol_base ILIKE :pattern ESCAPE '\\'
                       OR protocol_number ILIKE :pattern ESCAPE '\\'
                       OR COALESCE(document_number, '') ILIKE :pattern ESCAPE '\\'
                       OR COALESCE(title, '') ILIKE :pattern ESCAPE '\\'
                       OR COALESCE(pi_id, '') ILIKE :pattern ESCAPE '\\'
                       OR COALESCE(pi_email, '') ILIKE :pattern ESCAPE '\\'
                    GROUP BY protocol_base
                ) matching_families
                """)
                .param("hasQuery", StringUtils.hasText(normalized))
                .param("pattern", pattern)
                .query(Long.class)
                .single();

        List<IrbFamilyResponse> content = jdbcClient.sql("""
                WITH matching AS (
                    SELECT *
                    FROM archive.irb_protocol_version
                    WHERE :hasQuery = FALSE
                       OR protocol_base ILIKE :pattern ESCAPE '\\'
                       OR protocol_number ILIKE :pattern ESCAPE '\\'
                       OR COALESCE(document_number, '') ILIKE :pattern ESCAPE '\\'
                       OR COALESCE(title, '') ILIKE :pattern ESCAPE '\\'
                       OR COALESCE(pi_id, '') ILIKE :pattern ESCAPE '\\'
                       OR COALESCE(pi_email, '') ILIKE :pattern ESCAPE '\\'
                ),
                ranked AS (
                    SELECT
                        matching.*,
                        COUNT(*) OVER (
                            PARTITION BY protocol_base
                        ) AS version_count,
                        ROW_NUMBER() OVER (
                            PARTITION BY protocol_base
                            ORDER BY
                                COALESCE(sequence_number, -1) DESC,
                                protocol_id DESC
                        ) AS row_number
                    FROM matching
                )
                SELECT
                    protocol_base,
                    version_count,
                    protocol_id AS latest_protocol_id,
                    protocol_number AS latest_protocol_number,
                    title AS latest_title,
                    protocol_status AS latest_status,
                    protocol_type AS latest_type,
                    pi_id,
                    pi_email,
                    approval_date AS latest_approval_date
                FROM ranked
                WHERE row_number = 1
                ORDER BY protocol_base DESC
                LIMIT :size
                OFFSET :offset
                """)
                .param("hasQuery", StringUtils.hasText(normalized))
                .param("pattern", pattern)
                .param("size", size)
                .param("offset", offset)
                .query((resultSet, rowNumber) ->
                        new IrbFamilyResponse(
                                resultSet.getString("protocol_base"),
                                resultSet.getLong("version_count"),
                                resultSet.getObject(
                                        "latest_protocol_id",
                                        Long.class
                                ),
                                resultSet.getString(
                                        "latest_protocol_number"
                                ),
                                resultSet.getString("latest_title"),
                                resultSet.getString("latest_status"),
                                resultSet.getString("latest_type"),
                                resultSet.getString("pi_id"),
                                resultSet.getString("pi_email"),
                                resultSet.getObject(
                                        "latest_approval_date",
                                        java.time.LocalDate.class
                                )
                        )
                )
                .list();

        return page(content, page, size, total);
    }

    public PageResponse<IrbHistoryResponse> findHistory(
            String query,
            int page,
            int size
    ) {
        String normalized = normalize(query);
        String pattern = "%" + escapeLike(normalized) + "%";
        int offset = page * size;

        long total = jdbcClient.sql("""
                SELECT COUNT(*)
                FROM archive.irb_protocol_version
                WHERE :hasQuery = FALSE
                   OR protocol_base ILIKE :pattern ESCAPE '\\'
                   OR protocol_number ILIKE :pattern ESCAPE '\\'
                   OR COALESCE(document_number, '') ILIKE :pattern ESCAPE '\\'
                   OR COALESCE(crc_protocol_num, '') ILIKE :pattern ESCAPE '\\'
                   OR COALESCE(title, '') ILIKE :pattern ESCAPE '\\'
                   OR COALESCE(pi_id, '') ILIKE :pattern ESCAPE '\\'
                   OR COALESCE(pi_email, '') ILIKE :pattern ESCAPE '\\'
                   OR COALESCE(protocol_status, '') ILIKE :pattern ESCAPE '\\'
                   OR COALESCE(protocol_type, '') ILIKE :pattern ESCAPE '\\'
                """)
                .param("hasQuery", StringUtils.hasText(normalized))
                .param("pattern", pattern)
                .query(Long.class)
                .single();

        List<IrbHistoryResponse> content = jdbcClient.sql("""
                SELECT
                    protocol_id,
                    protocol_base,
                    protocol_number,
                    sequence_number,
                    document_number,
                    crc_protocol_num,
                    title,
                    protocol_status,
                    protocol_type,
                    pi_id,
                    pi_email,
                    approval_date,
                    expiration_date
                FROM archive.irb_protocol_version
                WHERE :hasQuery = FALSE
                   OR protocol_base ILIKE :pattern ESCAPE '\\'
                   OR protocol_number ILIKE :pattern ESCAPE '\\'
                   OR COALESCE(document_number, '') ILIKE :pattern ESCAPE '\\'
                   OR COALESCE(crc_protocol_num, '') ILIKE :pattern ESCAPE '\\'
                   OR COALESCE(title, '') ILIKE :pattern ESCAPE '\\'
                   OR COALESCE(pi_id, '') ILIKE :pattern ESCAPE '\\'
                   OR COALESCE(pi_email, '') ILIKE :pattern ESCAPE '\\'
                   OR COALESCE(protocol_status, '') ILIKE :pattern ESCAPE '\\'
                   OR COALESCE(protocol_type, '') ILIKE :pattern ESCAPE '\\'
                ORDER BY
                    protocol_base DESC,
                    COALESCE(sequence_number, -1) DESC,
                    protocol_id DESC
                LIMIT :size
                OFFSET :offset
                """)
                .param("hasQuery", StringUtils.hasText(normalized))
                .param("pattern", pattern)
                .param("size", size)
                .param("offset", offset)
                .query((resultSet, rowNumber) ->
                        new IrbHistoryResponse(
                                resultSet.getObject(
                                        "protocol_id",
                                        Long.class
                                ),
                                resultSet.getString("protocol_base"),
                                resultSet.getString("protocol_number"),
                                resultSet.getObject(
                                        "sequence_number",
                                        Integer.class
                                ),
                                resultSet.getString("document_number"),
                                resultSet.getString("crc_protocol_num"),
                                resultSet.getString("title"),
                                resultSet.getString("protocol_status"),
                                resultSet.getString("protocol_type"),
                                resultSet.getString("pi_id"),
                                resultSet.getString("pi_email"),
                                resultSet.getObject(
                                        "approval_date",
                                        java.time.LocalDate.class
                                ),
                                resultSet.getObject(
                                        "expiration_date",
                                        java.time.LocalDate.class
                                )
                        )
                )
                .list();

        return page(content, page, size, total);
    }

    private static String normalize(String query) {
        return query == null ? "" : query.trim();
    }

    private static String escapeLike(String value) {
        return value
                .replace("\\", "\\\\")
                .replace("%", "\\%")
                .replace("_", "\\_");
    }

    private static <T> PageResponse<T> page(
            List<T> content,
            int page,
            int size,
            long total
    ) {
        int totalPages = total == 0
                ? 0
                : (int) Math.ceil((double) total / size);

        return new PageResponse<>(
                content,
                page,
                size,
                total,
                totalPages,
                page == 0,
                totalPages == 0 || page >= totalPages - 1
        );
    }
}
