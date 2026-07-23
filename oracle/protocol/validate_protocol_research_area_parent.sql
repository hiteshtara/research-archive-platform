/*
 * Focused, read-only parent-resolution evidence for the exact source used by
 * export_protocol_research_areas.sql.
 */
WITH source_rows AS (
    SELECT
        pra.ID,
        pra.PROTOCOL_ID,
        pra.PROTOCOL_NUMBER,
        pra.SEQUENCE_NUMBER
    FROM KCOEUS.PROTOCOL_RESEARCH_AREAS pra
),
evaluated AS (
    SELECT
        source_rows.*,
        direct_parent.PROTOCOL_NUMBER AS direct_protocol_number,
        direct_parent.SEQUENCE_NUMBER AS direct_sequence_number,
        (
            SELECT COUNT(*)
            FROM KCOEUS.PROTOCOL tuple_parent
            WHERE tuple_parent.PROTOCOL_NUMBER =
                  source_rows.PROTOCOL_NUMBER
              AND tuple_parent.SEQUENCE_NUMBER =
                  source_rows.SEQUENCE_NUMBER
        ) AS tuple_parent_count
    FROM source_rows
    LEFT JOIN KCOEUS.PROTOCOL direct_parent
      ON direct_parent.PROTOCOL_ID = source_rows.PROTOCOL_ID
)
SELECT
    COUNT(*) AS total_rows,
    SUM(
        CASE
            WHEN direct_protocol_number = PROTOCOL_NUMBER
             AND direct_sequence_number = SEQUENCE_NUMBER
            THEN 1 ELSE 0
        END
    ) AS direct_id_matches,
    SUM(
        CASE
            WHEN direct_protocol_number != PROTOCOL_NUMBER
              OR direct_sequence_number != SEQUENCE_NUMBER
              OR direct_protocol_number IS NULL
            THEN 1 ELSE 0
        END
    ) AS direct_id_mismatches,
    SUM(CASE WHEN tuple_parent_count = 0 THEN 1 ELSE 0 END)
        AS missing_tuple_parents,
    SUM(CASE WHEN tuple_parent_count > 1 THEN 1 ELSE 0 END)
        AS ambiguous_tuple_parents,
    ROUND(
        100 * SUM(
            CASE
                WHEN direct_protocol_number != PROTOCOL_NUMBER
                  OR direct_sequence_number != SEQUENCE_NUMBER
                  OR direct_protocol_number IS NULL
                THEN 1 ELSE 0
            END
        ) / NULLIF(COUNT(*), 0),
        4
    ) AS direct_id_mismatch_percentage
FROM evaluated;
