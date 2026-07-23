/*
 * PROTO_AMEND_RENEWAL historical parent investigation.
 *
 * The descriptor exposes a direct Protocol reference and the Protocol
 * number/sequence tuple. It exposes no submission or alternate owner key.
 * This script is SELECT-only and produces seven independent result sets.
 */

/* Result Set 1: source identity, cardinality, and null coverage. */
SELECT
    COUNT(*) AS total_rows,
    COUNT(DISTINCT renewal.PROTO_AMEND_RENEWAL_ID)
        AS distinct_primary_keys,
    COUNT(*) - COUNT(DISTINCT renewal.PROTO_AMEND_RENEWAL_ID)
        AS duplicate_primary_keys,
    SUM(
        CASE
            WHEN renewal.PROTO_AMEND_RENEWAL_ID IS NULL
            THEN 1 ELSE 0
        END
    ) AS null_primary_keys,
    SUM(CASE WHEN renewal.PROTOCOL_ID IS NULL THEN 1 ELSE 0 END)
        AS null_protocol_ids,
    SUM(CASE WHEN renewal.PROTOCOL_NUMBER IS NULL THEN 1 ELSE 0 END)
        AS null_protocol_numbers,
    SUM(CASE WHEN renewal.SEQUENCE_NUMBER IS NULL THEN 1 ELSE 0 END)
        AS null_sequence_numbers,
    COUNT(DISTINCT renewal.PROTOCOL_ID) AS distinct_protocol_ids,
    COUNT(
        DISTINCT renewal.PROTOCOL_NUMBER || ':' ||
        TO_CHAR(renewal.SEQUENCE_NUMBER)
    ) AS distinct_number_sequence_tuples
FROM KCOEUS.PROTO_AMEND_RENEWAL renewal;

/* Result Set 2: DIRECT_PROTOCOL_ID evidence. */
WITH evaluated AS (
    SELECT
        renewal.PROTO_AMEND_RENEWAL_ID,
        renewal.PROTOCOL_ID,
        renewal.PROTOCOL_NUMBER,
        renewal.SEQUENCE_NUMBER,
        direct_parent.PROTOCOL_ID AS direct_parent_id,
        direct_parent.PROTOCOL_NUMBER AS direct_parent_number,
        direct_parent.SEQUENCE_NUMBER AS direct_parent_sequence
    FROM KCOEUS.PROTO_AMEND_RENEWAL renewal
    LEFT JOIN KCOEUS.PROTOCOL direct_parent
      ON direct_parent.PROTOCOL_ID = renewal.PROTOCOL_ID
)
SELECT
    COUNT(*) AS total_rows,
    SUM(CASE WHEN direct_parent_id IS NOT NULL THEN 1 ELSE 0 END)
        AS direct_parent_found,
    SUM(CASE WHEN direct_parent_id IS NULL THEN 1 ELSE 0 END)
        AS direct_parent_missing,
    SUM(
        CASE
            WHEN direct_parent_id IS NOT NULL
             AND direct_parent_number = PROTOCOL_NUMBER
             AND direct_parent_sequence = SEQUENCE_NUMBER
            THEN 1 ELSE 0
        END
    ) AS direct_exact_matches,
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
        renewal.PROTO_AMEND_RENEWAL_ID,
        renewal.PROTOCOL_ID,
        (
            SELECT COUNT(*)
            FROM KCOEUS.PROTOCOL tuple_parent
            WHERE tuple_parent.PROTOCOL_NUMBER =
                  renewal.PROTOCOL_NUMBER
              AND tuple_parent.SEQUENCE_NUMBER =
                  renewal.SEQUENCE_NUMBER
        ) AS tuple_parent_count,
        (
            SELECT MAX(tuple_parent.PROTOCOL_ID)
            FROM KCOEUS.PROTOCOL tuple_parent
            WHERE tuple_parent.PROTOCOL_NUMBER =
                  renewal.PROTOCOL_NUMBER
              AND tuple_parent.SEQUENCE_NUMBER =
                  renewal.SEQUENCE_NUMBER
        ) AS tuple_parent_id
    FROM KCOEUS.PROTO_AMEND_RENEWAL renewal
)
SELECT
    COUNT(*) AS total_rows,
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

/* Result Set 4: owner-chain eligibility.
 *
 * The descriptor contains no submission/owner key. This metadata result
 * confirms whether BU Oracle added any candidate physical column before an
 * owner-chain hypothesis is considered.
 */
SELECT
    'PROTO_AMEND_RENEWAL' AS table_name,
    SUM(
        CASE WHEN column_row.COLUMN_NAME = 'SUBMISSION_ID_FK'
             THEN 1 ELSE 0 END
    ) AS submission_id_fk_columns,
    SUM(
        CASE WHEN column_row.COLUMN_NAME = 'SUBMISSION_NUMBER'
             THEN 1 ELSE 0 END
    ) AS submission_number_columns,
    SUM(
        CASE WHEN column_row.COLUMN_NAME LIKE '%OWNER%ID%'
             THEN 1 ELSE 0 END
    ) AS other_owner_id_columns,
    CASE
        WHEN SUM(
            CASE
                WHEN column_row.COLUMN_NAME = 'SUBMISSION_ID_FK'
                THEN 1 ELSE 0
            END
        ) = 0
        THEN 'OWNER_CHAIN_NOT_SUPPORTED'
        ELSE 'OWNER_CHAIN_REQUIRES_REVIEW'
    END AS owner_chain_status
FROM ALL_TAB_COLUMNS column_row
WHERE column_row.OWNER = 'KCOEUS'
  AND column_row.TABLE_NAME = 'PROTO_AMEND_RENEWAL';

/* Result Set 5: mutually exclusive resolution-pattern counts. */
WITH evaluated AS (
    SELECT
        renewal.PROTO_AMEND_RENEWAL_ID,
        renewal.PROTOCOL_ID,
        renewal.PROTOCOL_NUMBER,
        renewal.SEQUENCE_NUMBER,
        direct_parent.PROTOCOL_ID AS direct_parent_id,
        direct_parent.PROTOCOL_NUMBER AS direct_parent_number,
        direct_parent.SEQUENCE_NUMBER AS direct_parent_sequence,
        (
            SELECT COUNT(*)
            FROM KCOEUS.PROTOCOL tuple_parent
            WHERE tuple_parent.PROTOCOL_NUMBER =
                  renewal.PROTOCOL_NUMBER
              AND tuple_parent.SEQUENCE_NUMBER =
                  renewal.SEQUENCE_NUMBER
        ) AS tuple_parent_count,
        (
            SELECT MAX(tuple_parent.PROTOCOL_ID)
            FROM KCOEUS.PROTOCOL tuple_parent
            WHERE tuple_parent.PROTOCOL_NUMBER =
                  renewal.PROTOCOL_NUMBER
              AND tuple_parent.SEQUENCE_NUMBER =
                  renewal.SEQUENCE_NUMBER
        ) AS tuple_parent_id
    FROM KCOEUS.PROTO_AMEND_RENEWAL renewal
    LEFT JOIN KCOEUS.PROTOCOL direct_parent
      ON direct_parent.PROTOCOL_ID = renewal.PROTOCOL_ID
),
classified AS (
    SELECT
        evaluated.*,
        CASE
            WHEN direct_parent_id IS NULL THEN 'DIRECT_MISSING'
            WHEN tuple_parent_count = 0 THEN 'TUPLE_MISSING'
            WHEN tuple_parent_count > 1 THEN 'TUPLE_AMBIGUOUS'
            WHEN direct_parent_number = PROTOCOL_NUMBER
             AND direct_parent_sequence = SEQUENCE_NUMBER
                THEN 'DIRECT_EXACT'
            WHEN tuple_parent_count = 1
             AND tuple_parent_id != direct_parent_id
                THEN 'TUPLE_EXACT_DIRECT_DIFFERS'
            ELSE 'UNEXPLAINED'
        END AS resolution_pattern
    FROM evaluated
)
SELECT
    resolution_pattern,
    COUNT(*) AS renewal_rows
FROM classified
GROUP BY resolution_pattern
ORDER BY resolution_pattern;

/* Result Set 6: up to 25 identifier-only examples per pattern. */
WITH evaluated AS (
    SELECT
        renewal.PROTO_AMEND_RENEWAL_ID,
        renewal.PROTO_AMEND_REN_NUMBER,
        renewal.PROTOCOL_ID,
        renewal.PROTOCOL_NUMBER,
        renewal.SEQUENCE_NUMBER,
        renewal.DATE_CREATED,
        direct_parent.PROTOCOL_ID AS direct_parent_id,
        direct_parent.PROTOCOL_NUMBER AS direct_parent_number,
        direct_parent.SEQUENCE_NUMBER AS direct_parent_sequence,
        (
            SELECT COUNT(*)
            FROM KCOEUS.PROTOCOL tuple_parent
            WHERE tuple_parent.PROTOCOL_NUMBER =
                  renewal.PROTOCOL_NUMBER
              AND tuple_parent.SEQUENCE_NUMBER =
                  renewal.SEQUENCE_NUMBER
        ) AS tuple_parent_count,
        (
            SELECT MAX(tuple_parent.PROTOCOL_ID)
            FROM KCOEUS.PROTOCOL tuple_parent
            WHERE tuple_parent.PROTOCOL_NUMBER =
                  renewal.PROTOCOL_NUMBER
              AND tuple_parent.SEQUENCE_NUMBER =
                  renewal.SEQUENCE_NUMBER
        ) AS tuple_parent_id
    FROM KCOEUS.PROTO_AMEND_RENEWAL renewal
    LEFT JOIN KCOEUS.PROTOCOL direct_parent
      ON direct_parent.PROTOCOL_ID = renewal.PROTOCOL_ID
),
classified AS (
    SELECT
        evaluated.*,
        CASE
            WHEN direct_parent_id IS NULL THEN 'DIRECT_MISSING'
            WHEN tuple_parent_count = 0 THEN 'TUPLE_MISSING'
            WHEN tuple_parent_count > 1 THEN 'TUPLE_AMBIGUOUS'
            WHEN direct_parent_number = PROTOCOL_NUMBER
             AND direct_parent_sequence = SEQUENCE_NUMBER
                THEN 'DIRECT_EXACT'
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
            ORDER BY PROTO_AMEND_RENEWAL_ID
        ) AS example_number
    FROM classified
)
SELECT
    resolution_pattern,
    PROTO_AMEND_RENEWAL_ID AS proto_amend_renewal_id,
    PROTO_AMEND_REN_NUMBER AS proto_amend_renewal_number,
    PROTOCOL_ID AS source_protocol_id,
    PROTOCOL_NUMBER AS source_protocol_number,
    SEQUENCE_NUMBER AS source_sequence_number,
    direct_parent_id,
    direct_parent_number,
    direct_parent_sequence,
    tuple_parent_count,
    tuple_parent_id,
    DATE_CREATED AS date_created
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
  AND child_constraint.TABLE_NAME = 'PROTO_AMEND_RENEWAL'
  AND child_constraint.CONSTRAINT_TYPE = 'R'
ORDER BY child_constraint.CONSTRAINT_NAME, child_column.POSITION;
