/*
 * Focused, read-only parent validation matching
 * export_protocol_submissions.sql.
 */
WITH source_rows AS (
    SELECT
        submission.SUBMISSION_ID,
        submission.PROTOCOL_ID,
        submission.PROTOCOL_NUMBER,
        submission.SEQUENCE_NUMBER
    FROM KCOEUS.PROTOCOL_SUBMISSION submission
),
evaluated AS (
    SELECT
        source_rows.SUBMISSION_ID,
        source_rows.PROTOCOL_ID,
        source_rows.PROTOCOL_NUMBER,
        source_rows.SEQUENCE_NUMBER,
        direct_parent.PROTOCOL_ID AS direct_parent_id,
        direct_parent.PROTOCOL_NUMBER AS direct_parent_number,
        direct_parent.SEQUENCE_NUMBER AS direct_parent_sequence,
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
            WHEN direct_parent_id IS NOT NULL
             AND direct_parent_number = PROTOCOL_NUMBER
             AND direct_parent_sequence = SEQUENCE_NUMBER
            THEN 1 ELSE 0
        END
    ) AS direct_id_matches,
    SUM(
        CASE
            WHEN direct_parent_id IS NOT NULL
             AND (
                  direct_parent_number != PROTOCOL_NUMBER
                  OR direct_parent_sequence != SEQUENCE_NUMBER
             )
            THEN 1 ELSE 0
        END
    ) AS direct_id_mismatches,
    SUM(CASE WHEN direct_parent_id IS NULL THEN 1 ELSE 0 END)
        AS missing_direct_parents,
    COUNT(*) - COUNT(DISTINCT SUBMISSION_ID)
        AS duplicate_source_identifiers,
    SUM(CASE WHEN tuple_parent_count = 1 THEN 1 ELSE 0 END)
        AS unique_number_sequence_parents,
    SUM(CASE WHEN tuple_parent_count = 0 THEN 1 ELSE 0 END)
        AS missing_number_sequence_parents,
    SUM(CASE WHEN tuple_parent_count > 1 THEN 1 ELSE 0 END)
        AS ambiguous_number_sequence_parents
FROM evaluated;

/* Identifier-only examples for any direct-parent inconsistency. */
SELECT *
FROM (
    SELECT
        submission.SUBMISSION_ID AS submission_id,
        submission.PROTOCOL_ID AS source_protocol_id,
        submission.PROTOCOL_NUMBER AS source_protocol_number,
        submission.SEQUENCE_NUMBER AS source_sequence_number,
        direct_parent.PROTOCOL_NUMBER AS direct_protocol_number,
        direct_parent.SEQUENCE_NUMBER AS direct_sequence_number
    FROM KCOEUS.PROTOCOL_SUBMISSION submission
    LEFT JOIN KCOEUS.PROTOCOL direct_parent
      ON direct_parent.PROTOCOL_ID = submission.PROTOCOL_ID
    WHERE direct_parent.PROTOCOL_ID IS NULL
       OR direct_parent.PROTOCOL_NUMBER != submission.PROTOCOL_NUMBER
       OR direct_parent.SEQUENCE_NUMBER != submission.SEQUENCE_NUMBER
    ORDER BY submission.SUBMISSION_ID
)
WHERE ROWNUM <= 20;
