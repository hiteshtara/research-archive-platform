/*
 * PROTOCOL_ACTIONS historical parent investigation.
 *
 * Authoritative descriptor relationships:
 *   PROTOCOL_ACTIONS.PROTOCOL_ID
 *       -> PROTOCOL.PROTOCOL_ID
 *   PROTOCOL_ACTIONS.SUBMISSION_ID_FK
 *       -> PROTOCOL_SUBMISSION.SUBMISSION_ID
 *
 * This script is SELECT-only. Each numbered section is a separate result
 * set and must be retained for review before an archive strategy is chosen.
 */

/* Result Set 1: source identity and null summary. */
SELECT
    COUNT(*) AS total_action_rows,
    COUNT(DISTINCT action.PROTOCOL_ACTION_ID) AS distinct_action_ids,
    COUNT(*) - COUNT(DISTINCT action.PROTOCOL_ACTION_ID)
        AS duplicate_source_ids,
    SUM(CASE WHEN action.PROTOCOL_ACTION_ID IS NULL THEN 1 ELSE 0 END)
        AS null_action_ids,
    SUM(CASE WHEN action.PROTOCOL_ID IS NULL THEN 1 ELSE 0 END)
        AS null_protocol_ids,
    SUM(CASE WHEN action.PROTOCOL_NUMBER IS NULL THEN 1 ELSE 0 END)
        AS null_protocol_numbers,
    SUM(CASE WHEN action.SEQUENCE_NUMBER IS NULL THEN 1 ELSE 0 END)
        AS null_sequence_numbers,
    COUNT(DISTINCT action.PROTOCOL_ID) AS distinct_protocol_ids,
    COUNT(
        DISTINCT action.PROTOCOL_NUMBER || ':' ||
        TO_CHAR(action.SEQUENCE_NUMBER)
    ) AS distinct_number_sequence_tuples
FROM KCOEUS.PROTOCOL_ACTIONS action;

/* Result Set 2: DIRECT_PROTOCOL_ID evidence. */
WITH evaluated AS (
    SELECT
        action.PROTOCOL_ACTION_ID,
        action.PROTOCOL_ID,
        action.PROTOCOL_NUMBER,
        action.SEQUENCE_NUMBER,
        direct_parent.PROTOCOL_ID AS direct_parent_id,
        direct_parent.PROTOCOL_NUMBER AS direct_parent_number,
        direct_parent.SEQUENCE_NUMBER AS direct_parent_sequence
    FROM KCOEUS.PROTOCOL_ACTIONS action
    LEFT JOIN KCOEUS.PROTOCOL direct_parent
      ON direct_parent.PROTOCOL_ID = action.PROTOCOL_ID
)
SELECT
    COUNT(*) AS total_action_rows,
    SUM(CASE WHEN direct_parent_id IS NOT NULL THEN 1 ELSE 0 END)
        AS direct_parent_found,
    SUM(CASE WHEN direct_parent_id IS NULL THEN 1 ELSE 0 END)
        AS direct_parent_missing,
    SUM(
        CASE
            WHEN direct_parent_id IS NOT NULL
             AND direct_parent_number = PROTOCOL_NUMBER
            THEN 1 ELSE 0
        END
    ) AS exact_protocol_number_match,
    SUM(
        CASE
            WHEN direct_parent_id IS NOT NULL
             AND direct_parent_sequence = SEQUENCE_NUMBER
            THEN 1 ELSE 0
        END
    ) AS exact_sequence_match,
    SUM(
        CASE
            WHEN direct_parent_id IS NOT NULL
             AND direct_parent_number = PROTOCOL_NUMBER
             AND direct_parent_sequence = SEQUENCE_NUMBER
            THEN 1 ELSE 0
        END
    ) AS exact_number_and_sequence_match,
    SUM(
        CASE
            WHEN direct_parent_id IS NOT NULL
             AND direct_parent_number != PROTOCOL_NUMBER
             AND direct_parent_sequence = SEQUENCE_NUMBER
            THEN 1 ELSE 0
        END
    ) AS number_mismatch_only,
    SUM(
        CASE
            WHEN direct_parent_id IS NOT NULL
             AND direct_parent_number = PROTOCOL_NUMBER
             AND direct_parent_sequence != SEQUENCE_NUMBER
            THEN 1 ELSE 0
        END
    ) AS sequence_mismatch_only,
    SUM(
        CASE
            WHEN direct_parent_id IS NOT NULL
             AND direct_parent_number != PROTOCOL_NUMBER
             AND direct_parent_sequence != SEQUENCE_NUMBER
            THEN 1 ELSE 0
        END
    ) AS both_number_and_sequence_mismatch,
    ROUND(
        100 * SUM(
            CASE
                WHEN direct_parent_id IS NULL
                  OR direct_parent_number != PROTOCOL_NUMBER
                  OR direct_parent_sequence != SEQUENCE_NUMBER
                THEN 1 ELSE 0
            END
        ) / NULLIF(COUNT(*), 0),
        4
    ) AS direct_mismatch_percentage
FROM evaluated;

/* Result Set 3: NUMBER_SEQUENCE evidence. */
WITH evaluated AS (
    SELECT
        action.PROTOCOL_ACTION_ID,
        action.PROTOCOL_ID,
        (
            SELECT COUNT(*)
            FROM KCOEUS.PROTOCOL tuple_parent
            WHERE tuple_parent.PROTOCOL_NUMBER = action.PROTOCOL_NUMBER
              AND tuple_parent.SEQUENCE_NUMBER = action.SEQUENCE_NUMBER
        ) AS tuple_parent_count,
        (
            SELECT MAX(tuple_parent.PROTOCOL_ID)
            FROM KCOEUS.PROTOCOL tuple_parent
            WHERE tuple_parent.PROTOCOL_NUMBER = action.PROTOCOL_NUMBER
              AND tuple_parent.SEQUENCE_NUMBER = action.SEQUENCE_NUMBER
        ) AS tuple_parent_id
    FROM KCOEUS.PROTOCOL_ACTIONS action
)
SELECT
    COUNT(*) AS total_action_rows,
    SUM(CASE WHEN tuple_parent_count = 1 THEN 1 ELSE 0 END)
        AS unique_tuple_parents,
    SUM(CASE WHEN tuple_parent_count = 0 THEN 1 ELSE 0 END)
        AS missing_tuple_parents,
    SUM(CASE WHEN tuple_parent_count > 1 THEN 1 ELSE 0 END)
        AS ambiguous_tuple_parents,
    SUM(
        CASE
            WHEN tuple_parent_count = 1
             AND tuple_parent_id = PROTOCOL_ID
            THEN 1 ELSE 0
        END
    ) AS tuple_parent_equals_direct_parent,
    SUM(
        CASE
            WHEN tuple_parent_count = 1
             AND tuple_parent_id != PROTOCOL_ID
            THEN 1 ELSE 0
        END
    ) AS tuple_parent_differs_from_direct_parent
FROM evaluated;

/* Result Set 4: verified PROTOCOL_SUBMISSION owner-chain evidence. */
WITH evaluated AS (
    SELECT
        action.PROTOCOL_ACTION_ID,
        action.PROTOCOL_ID,
        action.PROTOCOL_NUMBER,
        action.SEQUENCE_NUMBER,
        action.SUBMISSION_ID_FK,
        action.SUBMISSION_NUMBER,
        submission.SUBMISSION_ID AS owner_submission_id,
        submission.PROTOCOL_ID AS owner_protocol_id,
        submission.PROTOCOL_NUMBER AS owner_protocol_number,
        submission.SEQUENCE_NUMBER AS owner_sequence_number,
        submission.SUBMISSION_NUMBER AS owner_submission_number,
        owner_parent.PROTOCOL_ID AS owner_parent_id,
        (
            SELECT COUNT(*)
            FROM KCOEUS.PROTOCOL tuple_parent
            WHERE tuple_parent.PROTOCOL_NUMBER = action.PROTOCOL_NUMBER
              AND tuple_parent.SEQUENCE_NUMBER = action.SEQUENCE_NUMBER
        ) AS tuple_parent_count,
        (
            SELECT MAX(tuple_parent.PROTOCOL_ID)
            FROM KCOEUS.PROTOCOL tuple_parent
            WHERE tuple_parent.PROTOCOL_NUMBER = action.PROTOCOL_NUMBER
              AND tuple_parent.SEQUENCE_NUMBER = action.SEQUENCE_NUMBER
        ) AS tuple_parent_id
    FROM KCOEUS.PROTOCOL_ACTIONS action
    LEFT JOIN KCOEUS.PROTOCOL_SUBMISSION submission
      ON submission.SUBMISSION_ID = action.SUBMISSION_ID_FK
    LEFT JOIN KCOEUS.PROTOCOL owner_parent
      ON owner_parent.PROTOCOL_ID = submission.PROTOCOL_ID
)
SELECT
    COUNT(*) AS total_action_rows,
    SUM(CASE WHEN SUBMISSION_ID_FK IS NOT NULL THEN 1 ELSE 0 END)
        AS rows_with_submission_reference,
    SUM(CASE WHEN owner_submission_id IS NOT NULL THEN 1 ELSE 0 END)
        AS owner_row_found,
    SUM(
        CASE
            WHEN SUBMISSION_ID_FK IS NOT NULL
             AND owner_submission_id IS NULL
            THEN 1 ELSE 0
        END
    ) AS owner_row_missing,
    SUM(CASE WHEN owner_parent_id IS NOT NULL THEN 1 ELSE 0 END)
        AS owner_protocol_parent_found,
    SUM(
        CASE
            WHEN owner_submission_id IS NOT NULL
             AND owner_parent_id IS NOT NULL
             AND owner_protocol_number = PROTOCOL_NUMBER
             AND owner_sequence_number = SEQUENCE_NUMBER
            THEN 1 ELSE 0
        END
    ) AS owner_matches_action_number_sequence,
    SUM(
        CASE
            WHEN owner_submission_id IS NOT NULL
             AND owner_submission_number = SUBMISSION_NUMBER
            THEN 1 ELSE 0
        END
    ) AS owner_matches_action_submission_number,
    SUM(
        CASE
            WHEN owner_parent_id IS NOT NULL
             AND owner_parent_id = PROTOCOL_ID
            THEN 1 ELSE 0
        END
    ) AS owner_parent_matches_direct_id,
    SUM(
        CASE
            WHEN owner_parent_id IS NOT NULL
             AND tuple_parent_count = 1
             AND owner_parent_id = tuple_parent_id
            THEN 1 ELSE 0
        END
    ) AS owner_parent_matches_tuple_parent,
    SUM(
        CASE
            WHEN owner_parent_id IS NOT NULL
             AND owner_protocol_number = PROTOCOL_NUMBER
             AND owner_sequence_number = SEQUENCE_NUMBER
             AND tuple_parent_count = 1
             AND owner_parent_id = tuple_parent_id
            THEN 1 ELSE 0
        END
    ) AS owner_chain_exact
FROM evaluated;

/* Result Set 5: mutually exclusive resolution-pattern counts. */
WITH evaluated AS (
    SELECT
        action.PROTOCOL_ACTION_ID,
        action.PROTOCOL_ID,
        action.PROTOCOL_NUMBER,
        action.SEQUENCE_NUMBER,
        action.SUBMISSION_ID_FK,
        direct_parent.PROTOCOL_ID AS direct_parent_id,
        direct_parent.PROTOCOL_NUMBER AS direct_parent_number,
        direct_parent.SEQUENCE_NUMBER AS direct_parent_sequence,
        submission.SUBMISSION_ID AS owner_submission_id,
        submission.PROTOCOL_ID AS owner_protocol_id,
        submission.PROTOCOL_NUMBER AS owner_protocol_number,
        submission.SEQUENCE_NUMBER AS owner_sequence_number,
        owner_parent.PROTOCOL_ID AS owner_parent_id,
        (
            SELECT COUNT(*)
            FROM KCOEUS.PROTOCOL tuple_parent
            WHERE tuple_parent.PROTOCOL_NUMBER = action.PROTOCOL_NUMBER
              AND tuple_parent.SEQUENCE_NUMBER = action.SEQUENCE_NUMBER
        ) AS tuple_parent_count,
        (
            SELECT MAX(tuple_parent.PROTOCOL_ID)
            FROM KCOEUS.PROTOCOL tuple_parent
            WHERE tuple_parent.PROTOCOL_NUMBER = action.PROTOCOL_NUMBER
              AND tuple_parent.SEQUENCE_NUMBER = action.SEQUENCE_NUMBER
        ) AS tuple_parent_id
    FROM KCOEUS.PROTOCOL_ACTIONS action
    LEFT JOIN KCOEUS.PROTOCOL direct_parent
      ON direct_parent.PROTOCOL_ID = action.PROTOCOL_ID
    LEFT JOIN KCOEUS.PROTOCOL_SUBMISSION submission
      ON submission.SUBMISSION_ID = action.SUBMISSION_ID_FK
    LEFT JOIN KCOEUS.PROTOCOL owner_parent
      ON owner_parent.PROTOCOL_ID = submission.PROTOCOL_ID
),
classified AS (
    SELECT
        evaluated.*,
        CASE
            WHEN direct_parent_id IS NULL THEN 'DIRECT_MISSING'
            WHEN tuple_parent_count = 0 THEN 'TUPLE_MISSING'
            WHEN tuple_parent_count > 1 THEN 'TUPLE_AMBIGUOUS'
            WHEN SUBMISSION_ID_FK IS NOT NULL
             AND owner_submission_id IS NULL
                THEN 'OWNER_MISSING'
            WHEN direct_parent_number = PROTOCOL_NUMBER
             AND direct_parent_sequence = SEQUENCE_NUMBER
                THEN 'DIRECT_EXACT'
            WHEN owner_submission_id IS NOT NULL
             AND owner_parent_id IS NOT NULL
             AND owner_protocol_number = PROTOCOL_NUMBER
             AND owner_sequence_number = SEQUENCE_NUMBER
             AND owner_protocol_id = tuple_parent_id
                THEN 'OWNER_CHAIN_EXACT'
            WHEN tuple_parent_count = 1
             AND tuple_parent_id != direct_parent_id
                THEN 'TUPLE_EXACT_DIRECT_DIFFERS'
            ELSE 'UNEXPLAINED'
        END AS resolution_pattern
    FROM evaluated
)
SELECT
    resolution_pattern,
    COUNT(*) AS action_rows
FROM classified
GROUP BY resolution_pattern
ORDER BY resolution_pattern;

/* Result Set 6: up to 25 identifier-only examples per pattern. */
WITH evaluated AS (
    SELECT
        action.PROTOCOL_ACTION_ID,
        action.ACTION_ID,
        action.PROTOCOL_ID,
        action.PROTOCOL_NUMBER,
        action.SEQUENCE_NUMBER,
        action.SUBMISSION_ID_FK,
        action.SUBMISSION_NUMBER,
        action.PROTOCOL_ACTION_TYPE_CODE,
        action.ACTION_DATE,
        action.ACTUAL_ACTION_DATE,
        direct_parent.PROTOCOL_ID AS direct_parent_id,
        direct_parent.PROTOCOL_NUMBER AS direct_parent_number,
        direct_parent.SEQUENCE_NUMBER AS direct_parent_sequence,
        submission.SUBMISSION_ID AS owner_submission_id,
        submission.SUBMISSION_NUMBER AS owner_submission_number,
        submission.PROTOCOL_ID AS owner_protocol_id,
        submission.PROTOCOL_NUMBER AS owner_protocol_number,
        submission.SEQUENCE_NUMBER AS owner_sequence_number,
        owner_parent.PROTOCOL_ID AS owner_parent_id,
        (
            SELECT COUNT(*)
            FROM KCOEUS.PROTOCOL tuple_parent
            WHERE tuple_parent.PROTOCOL_NUMBER = action.PROTOCOL_NUMBER
              AND tuple_parent.SEQUENCE_NUMBER = action.SEQUENCE_NUMBER
        ) AS tuple_parent_count,
        (
            SELECT MAX(tuple_parent.PROTOCOL_ID)
            FROM KCOEUS.PROTOCOL tuple_parent
            WHERE tuple_parent.PROTOCOL_NUMBER = action.PROTOCOL_NUMBER
              AND tuple_parent.SEQUENCE_NUMBER = action.SEQUENCE_NUMBER
        ) AS tuple_parent_id
    FROM KCOEUS.PROTOCOL_ACTIONS action
    LEFT JOIN KCOEUS.PROTOCOL direct_parent
      ON direct_parent.PROTOCOL_ID = action.PROTOCOL_ID
    LEFT JOIN KCOEUS.PROTOCOL_SUBMISSION submission
      ON submission.SUBMISSION_ID = action.SUBMISSION_ID_FK
    LEFT JOIN KCOEUS.PROTOCOL owner_parent
      ON owner_parent.PROTOCOL_ID = submission.PROTOCOL_ID
),
classified AS (
    SELECT
        evaluated.*,
        CASE
            WHEN direct_parent_id IS NULL THEN 'DIRECT_MISSING'
            WHEN tuple_parent_count = 0 THEN 'TUPLE_MISSING'
            WHEN tuple_parent_count > 1 THEN 'TUPLE_AMBIGUOUS'
            WHEN SUBMISSION_ID_FK IS NOT NULL
             AND owner_submission_id IS NULL
                THEN 'OWNER_MISSING'
            WHEN direct_parent_number = PROTOCOL_NUMBER
             AND direct_parent_sequence = SEQUENCE_NUMBER
                THEN 'DIRECT_EXACT'
            WHEN owner_submission_id IS NOT NULL
             AND owner_parent_id IS NOT NULL
             AND owner_protocol_number = PROTOCOL_NUMBER
             AND owner_sequence_number = SEQUENCE_NUMBER
             AND owner_protocol_id = tuple_parent_id
                THEN 'OWNER_CHAIN_EXACT'
            WHEN tuple_parent_count = 1
             AND tuple_parent_id != direct_parent_id
                THEN 'TUPLE_EXACT_DIRECT_DIFFERS'
            ELSE 'UNEXPLAINED'
        END AS resolution_pattern
    FROM evaluated
),
ranked AS (
    SELECT
        classified.*,
        ROW_NUMBER() OVER (
            PARTITION BY resolution_pattern
            ORDER BY PROTOCOL_ACTION_ID
        ) AS example_number
    FROM classified
)
SELECT
    resolution_pattern,
    PROTOCOL_ACTION_ID AS protocol_action_id,
    ACTION_ID AS action_id,
    PROTOCOL_ID AS source_protocol_id,
    PROTOCOL_NUMBER AS source_protocol_number,
    SEQUENCE_NUMBER AS source_sequence_number,
    direct_parent_id,
    direct_parent_number,
    direct_parent_sequence,
    tuple_parent_count,
    tuple_parent_id,
    owner_submission_id,
    owner_submission_number,
    owner_protocol_id,
    owner_parent_id,
    owner_protocol_number,
    owner_sequence_number,
    SUBMISSION_ID_FK AS submission_id_fk,
    SUBMISSION_NUMBER AS action_submission_number,
    PROTOCOL_ACTION_TYPE_CODE AS action_type_code,
    ACTION_DATE AS action_date,
    ACTUAL_ACTION_DATE AS actual_action_date
FROM ranked
WHERE example_number <= 25
ORDER BY resolution_pattern, example_number;

/* Result Set 7: installed foreign keys and referenced columns. */
SELECT
    child_constraint.CONSTRAINT_NAME AS foreign_key_name,
    child_column.POSITION AS column_position,
    child_column.COLUMN_NAME AS child_column,
    parent_constraint.OWNER AS referenced_owner,
    parent_constraint.TABLE_NAME AS referenced_table,
    parent_column.COLUMN_NAME AS referenced_column,
    child_constraint.DELETE_RULE,
    child_constraint.STATUS
FROM ALL_CONSTRAINTS child_constraint
JOIN ALL_CONS_COLUMNS child_column
  ON child_column.OWNER = child_constraint.OWNER
 AND child_column.CONSTRAINT_NAME = child_constraint.CONSTRAINT_NAME
JOIN ALL_CONSTRAINTS parent_constraint
  ON parent_constraint.OWNER = child_constraint.R_OWNER
 AND parent_constraint.CONSTRAINT_NAME = child_constraint.R_CONSTRAINT_NAME
JOIN ALL_CONS_COLUMNS parent_column
  ON parent_column.OWNER = parent_constraint.OWNER
 AND parent_column.CONSTRAINT_NAME = parent_constraint.CONSTRAINT_NAME
 AND parent_column.POSITION = child_column.POSITION
WHERE child_constraint.OWNER = 'KCOEUS'
  AND child_constraint.TABLE_NAME = 'PROTOCOL_ACTIONS'
  AND child_constraint.CONSTRAINT_TYPE = 'R'
ORDER BY child_constraint.CONSTRAINT_NAME, child_column.POSITION;
