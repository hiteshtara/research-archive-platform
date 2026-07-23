/* Focused, read-only evidence for export_protocol_location.sql. */
WITH source_rows AS (
    SELECT
        pl.PROTOCOL_LOCATION_ID,
        pl.PROTOCOL_ID,
        pl.PROTOCOL_NUMBER,
        pl.SEQUENCE_NUMBER
    FROM KCOEUS.PROTOCOL_LOCATION pl
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

/*
 * Controlled fallback eligibility. Expected verified values:
 * missing_tuple_rows = 1,524
 * placeholder_tuple_rows = 1,524
 * placeholder_direct_parent_found = 1,524
 * placeholder_direct_parent_missing = 0
 * non_placeholder_missing_tuple_rows = 0
 */
WITH source_rows AS (
    SELECT
        pl.PROTOCOL_LOCATION_ID,
        pl.PROTOCOL_ID,
        pl.PROTOCOL_NUMBER,
        pl.SEQUENCE_NUMBER
    FROM KCOEUS.PROTOCOL_LOCATION pl
),
missing_tuples AS (
    SELECT source_rows.*
    FROM source_rows
    WHERE NOT EXISTS (
        SELECT 1
        FROM KCOEUS.PROTOCOL tuple_parent
        WHERE tuple_parent.PROTOCOL_NUMBER =
              source_rows.PROTOCOL_NUMBER
          AND tuple_parent.SEQUENCE_NUMBER =
              source_rows.SEQUENCE_NUMBER
    )
)
SELECT
    COUNT(*) AS missing_tuple_rows,
    SUM(
        CASE
            WHEN TRIM(missing_tuples.PROTOCOL_NUMBER) = '0'
             AND missing_tuples.SEQUENCE_NUMBER = 0
            THEN 1 ELSE 0
        END
    ) AS placeholder_tuple_rows,
    SUM(
        CASE
            WHEN TRIM(missing_tuples.PROTOCOL_NUMBER) = '0'
             AND missing_tuples.SEQUENCE_NUMBER = 0
             AND direct_parent.PROTOCOL_ID IS NOT NULL
            THEN 1 ELSE 0
        END
    ) AS placeholder_direct_parent_found,
    SUM(
        CASE
            WHEN TRIM(missing_tuples.PROTOCOL_NUMBER) = '0'
             AND missing_tuples.SEQUENCE_NUMBER = 0
             AND direct_parent.PROTOCOL_ID IS NULL
            THEN 1 ELSE 0
        END
    ) AS placeholder_direct_parent_missing,
    SUM(
        CASE
            WHEN TRIM(missing_tuples.PROTOCOL_NUMBER) != '0'
              OR missing_tuples.SEQUENCE_NUMBER != 0
            THEN 1 ELSE 0
        END
    ) AS non_placeholder_missing_tuple_rows
FROM missing_tuples
LEFT JOIN KCOEUS.PROTOCOL direct_parent
  ON direct_parent.PROTOCOL_ID = missing_tuples.PROTOCOL_ID;
