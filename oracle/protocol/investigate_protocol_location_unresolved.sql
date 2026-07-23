/*
 * Read-only investigation of unresolved PROTOCOL_LOCATION parents.
 *
 * "Unresolved" means that no KCOEUS.PROTOCOL row has the exact stored
 * PROTOCOL_NUMBER + SEQUENCE_NUMBER pair. No fallback is applied here.
 */

/* 1. Exact missing-tuple population. Confirms placeholder uniformity. */
SELECT
    location.PROTOCOL_NUMBER AS source_protocol_number,
    location.SEQUENCE_NUMBER AS source_sequence_number,
    COUNT(*) AS missing_tuple_rows,
    COUNT(DISTINCT location.PROTOCOL_ID)
        AS distinct_source_protocol_ids
FROM KCOEUS.PROTOCOL_LOCATION location
WHERE NOT EXISTS (
    SELECT 1
    FROM KCOEUS.PROTOCOL exact_parent
    WHERE exact_parent.PROTOCOL_NUMBER = location.PROTOCOL_NUMBER
      AND exact_parent.SEQUENCE_NUMBER = location.SEQUENCE_NUMBER
)
GROUP BY
    location.PROTOCOL_NUMBER,
    location.SEQUENCE_NUMBER
ORDER BY
    missing_tuple_rows DESC,
    source_protocol_number,
    source_sequence_number;

/*
 * 2. Direct-ID coverage for missing tuples.
 *
 * Safe hybrid implementation requires:
 * - missing_tuple_rows = 1,524
 * - placeholder_tuple_rows = 1,524
 * - direct_parent_found = 1,524
 * - distinct_direct_parents = distinct source PROTOCOL_ID count
 */
WITH unresolved AS (
    SELECT
        location.PROTOCOL_LOCATION_ID,
        location.PROTOCOL_ID,
        location.PROTOCOL_NUMBER,
        location.SEQUENCE_NUMBER
    FROM KCOEUS.PROTOCOL_LOCATION location
    WHERE NOT EXISTS (
        SELECT 1
        FROM KCOEUS.PROTOCOL exact_parent
        WHERE exact_parent.PROTOCOL_NUMBER = location.PROTOCOL_NUMBER
          AND exact_parent.SEQUENCE_NUMBER = location.SEQUENCE_NUMBER
    )
)
SELECT
    COUNT(*) AS missing_tuple_rows,
    SUM(
        CASE
            WHEN TRIM(unresolved.PROTOCOL_NUMBER) = '0'
             AND unresolved.SEQUENCE_NUMBER = 0
            THEN 1 ELSE 0
        END
    ) AS placeholder_tuple_rows,
    SUM(
        CASE WHEN direct_parent.PROTOCOL_ID IS NOT NULL THEN 1 ELSE 0 END
    ) AS direct_parent_found,
    SUM(
        CASE WHEN direct_parent.PROTOCOL_ID IS NULL THEN 1 ELSE 0 END
    ) AS direct_parent_missing,
    COUNT(DISTINCT direct_parent.PROTOCOL_ID)
        AS distinct_direct_parents
FROM unresolved
LEFT JOIN KCOEUS.PROTOCOL direct_parent
  ON direct_parent.PROTOCOL_ID = unresolved.PROTOCOL_ID;

/* 3. Failure patterns and conservative candidate counts. */
WITH unresolved AS (
    SELECT
        location.PROTOCOL_LOCATION_ID,
        location.PROTOCOL_ID,
        location.PROTOCOL_NUMBER,
        location.SEQUENCE_NUMBER
    FROM KCOEUS.PROTOCOL_LOCATION location
    WHERE NOT EXISTS (
        SELECT 1
        FROM KCOEUS.PROTOCOL exact_parent
        WHERE exact_parent.PROTOCOL_NUMBER = location.PROTOCOL_NUMBER
          AND exact_parent.SEQUENCE_NUMBER = location.SEQUENCE_NUMBER
    )
),
evaluated AS (
    SELECT
        unresolved.*,
        direct_parent.PROTOCOL_ID AS direct_parent_id,
        direct_parent.PROTOCOL_NUMBER AS direct_parent_number,
        direct_parent.SEQUENCE_NUMBER AS direct_parent_sequence,
        (
            SELECT COUNT(*)
            FROM KCOEUS.PROTOCOL family_parent
            WHERE family_parent.PROTOCOL_NUMBER =
                  unresolved.PROTOCOL_NUMBER
        ) AS exact_family_count,
        (
            SELECT COUNT(*)
            FROM KCOEUS.PROTOCOL normalized_parent
            WHERE UPPER(TRIM(normalized_parent.PROTOCOL_NUMBER)) =
                  UPPER(TRIM(unresolved.PROTOCOL_NUMBER))
              AND normalized_parent.SEQUENCE_NUMBER =
                  unresolved.SEQUENCE_NUMBER
        ) AS trim_case_tuple_count,
        (
            SELECT COUNT(*)
            FROM KCOEUS.PROTOCOL zero_parent
            WHERE LTRIM(TRIM(zero_parent.PROTOCOL_NUMBER), '0') =
                  LTRIM(TRIM(unresolved.PROTOCOL_NUMBER), '0')
              AND zero_parent.SEQUENCE_NUMBER =
                  unresolved.SEQUENCE_NUMBER
        ) AS leading_zero_tuple_count
    FROM unresolved
    LEFT JOIN KCOEUS.PROTOCOL direct_parent
      ON direct_parent.PROTOCOL_ID = unresolved.PROTOCOL_ID
)
SELECT
    COUNT(*) AS unresolved_rows,
    SUM(CASE WHEN direct_parent_id IS NOT NULL THEN 1 ELSE 0 END)
        AS direct_id_parent_found,
    SUM(
        CASE
            WHEN direct_parent_id IS NOT NULL
             AND UPPER(TRIM(direct_parent_number)) =
                 UPPER(TRIM(PROTOCOL_NUMBER))
            THEN 1 ELSE 0
        END
    ) AS direct_id_same_normalized_family,
    SUM(
        CASE
            WHEN direct_parent_id IS NOT NULL
             AND direct_parent_sequence = SEQUENCE_NUMBER
            THEN 1 ELSE 0
        END
    ) AS direct_id_same_sequence,
    SUM(CASE WHEN exact_family_count = 0 THEN 1 ELSE 0 END)
        AS exact_protocol_family_missing,
    SUM(CASE WHEN exact_family_count > 0 THEN 1 ELSE 0 END)
        AS exact_family_present_sequence_missing,
    SUM(CASE WHEN trim_case_tuple_count = 1 THEN 1 ELSE 0 END)
        AS unique_trim_case_candidates,
    SUM(CASE WHEN trim_case_tuple_count > 1 THEN 1 ELSE 0 END)
        AS ambiguous_trim_case_candidates,
    SUM(CASE WHEN leading_zero_tuple_count = 1 THEN 1 ELSE 0 END)
        AS unique_leading_zero_candidates,
    SUM(CASE WHEN leading_zero_tuple_count > 1 THEN 1 ELSE 0 END)
        AS ambiguous_leading_zero_candidates
FROM evaluated;

/* 4. Counts grouped by the mutually useful failure dimensions. */
WITH unresolved AS (
    SELECT
        location.PROTOCOL_LOCATION_ID,
        location.PROTOCOL_ID,
        location.PROTOCOL_NUMBER,
        location.SEQUENCE_NUMBER
    FROM KCOEUS.PROTOCOL_LOCATION location
    WHERE NOT EXISTS (
        SELECT 1
        FROM KCOEUS.PROTOCOL exact_parent
        WHERE exact_parent.PROTOCOL_NUMBER = location.PROTOCOL_NUMBER
          AND exact_parent.SEQUENCE_NUMBER = location.SEQUENCE_NUMBER
    )
),
classified AS (
    SELECT
        unresolved.*,
        direct_parent.PROTOCOL_ID AS direct_parent_id,
        direct_parent.PROTOCOL_NUMBER AS direct_parent_number,
        direct_parent.SEQUENCE_NUMBER AS direct_parent_sequence,
        CASE
            WHEN EXISTS (
                SELECT 1
                FROM KCOEUS.PROTOCOL family_parent
                WHERE family_parent.PROTOCOL_NUMBER =
                      unresolved.PROTOCOL_NUMBER
            ) THEN 1 ELSE 0
        END AS exact_family_found
    FROM unresolved
    LEFT JOIN KCOEUS.PROTOCOL direct_parent
      ON direct_parent.PROTOCOL_ID = unresolved.PROTOCOL_ID
)
SELECT
    CASE
        WHEN direct_parent_id IS NULL
            THEN 'DIRECT_ID_PARENT_MISSING'
        WHEN UPPER(TRIM(direct_parent_number)) !=
             UPPER(TRIM(PROTOCOL_NUMBER))
            THEN 'DIRECT_ID_DIFFERENT_FAMILY'
        WHEN direct_parent_sequence != SEQUENCE_NUMBER
            THEN 'DIRECT_ID_DIFFERENT_SEQUENCE'
        ELSE 'DIRECT_ID_NORMALIZED_MATCH'
    END AS direct_id_pattern,
    CASE
        WHEN exact_family_found = 0 THEN 'EXACT_FAMILY_MISSING'
        ELSE 'EXACT_FAMILY_PRESENT_SEQUENCE_MISSING'
    END AS tuple_failure_pattern,
    COUNT(*) AS row_count
FROM classified
GROUP BY
    CASE
        WHEN direct_parent_id IS NULL
            THEN 'DIRECT_ID_PARENT_MISSING'
        WHEN UPPER(TRIM(direct_parent_number)) !=
             UPPER(TRIM(PROTOCOL_NUMBER))
            THEN 'DIRECT_ID_DIFFERENT_FAMILY'
        WHEN direct_parent_sequence != SEQUENCE_NUMBER
            THEN 'DIRECT_ID_DIFFERENT_SEQUENCE'
        ELSE 'DIRECT_ID_NORMALIZED_MATCH'
    END,
    CASE
        WHEN exact_family_found = 0 THEN 'EXACT_FAMILY_MISSING'
        ELSE 'EXACT_FAMILY_PRESENT_SEQUENCE_MISSING'
    END
ORDER BY direct_id_pattern, tuple_failure_pattern;

/*
 * 5. Proof candidates.
 *
 * A candidate is marked HISTORICAL_VERSION_PROVEN only when conservative
 * normalization produces exactly one Protocol row and that row retains the
 * child's sequence. Direct ID alone is never considered proof.
 */
WITH unresolved AS (
    SELECT
        location.PROTOCOL_LOCATION_ID,
        location.PROTOCOL_ID,
        location.PROTOCOL_NUMBER,
        location.SEQUENCE_NUMBER
    FROM KCOEUS.PROTOCOL_LOCATION location
    WHERE NOT EXISTS (
        SELECT 1
        FROM KCOEUS.PROTOCOL exact_parent
        WHERE exact_parent.PROTOCOL_NUMBER = location.PROTOCOL_NUMBER
          AND exact_parent.SEQUENCE_NUMBER = location.SEQUENCE_NUMBER
    )
),
normalized_candidates AS (
    SELECT
        unresolved.PROTOCOL_LOCATION_ID,
        unresolved.PROTOCOL_ID AS source_protocol_id,
        unresolved.PROTOCOL_NUMBER AS source_protocol_number,
        unresolved.SEQUENCE_NUMBER AS source_sequence_number,
        candidate.PROTOCOL_ID AS candidate_protocol_id,
        candidate.PROTOCOL_NUMBER AS candidate_protocol_number,
        candidate.SEQUENCE_NUMBER AS candidate_sequence_number,
        COUNT(*) OVER (
            PARTITION BY unresolved.PROTOCOL_LOCATION_ID
        ) AS candidate_count
    FROM unresolved
    JOIN KCOEUS.PROTOCOL candidate
      ON UPPER(TRIM(candidate.PROTOCOL_NUMBER)) =
         UPPER(TRIM(unresolved.PROTOCOL_NUMBER))
     AND candidate.SEQUENCE_NUMBER = unresolved.SEQUENCE_NUMBER
)
SELECT
    COUNT(*) AS normalized_candidate_rows,
    SUM(CASE WHEN candidate_count = 1 THEN 1 ELSE 0 END)
        AS historical_version_proven,
    SUM(CASE WHEN candidate_count > 1 THEN 1 ELSE 0 END)
        AS ambiguous_historical_versions,
    SUM(
        CASE
            WHEN candidate_count = 1
             AND candidate_protocol_id = source_protocol_id
            THEN 1 ELSE 0
        END
    ) AS proven_candidate_also_matches_direct_id
FROM normalized_candidates;

/* 6. At least 25 identifier-only representative unresolved examples. */
SELECT *
FROM (
    SELECT
        location.PROTOCOL_LOCATION_ID AS protocol_location_id,
        location.PROTOCOL_ID AS source_protocol_id,
        location.PROTOCOL_NUMBER AS source_protocol_number,
        location.SEQUENCE_NUMBER AS source_sequence_number,
        direct_parent.PROTOCOL_NUMBER AS direct_protocol_number,
        direct_parent.SEQUENCE_NUMBER AS direct_sequence_number,
        (
            SELECT COUNT(*)
            FROM KCOEUS.PROTOCOL normalized_parent
            WHERE UPPER(TRIM(normalized_parent.PROTOCOL_NUMBER)) =
                  UPPER(TRIM(location.PROTOCOL_NUMBER))
              AND normalized_parent.SEQUENCE_NUMBER =
                  location.SEQUENCE_NUMBER
        ) AS normalized_candidate_count
    FROM KCOEUS.PROTOCOL_LOCATION location
    LEFT JOIN KCOEUS.PROTOCOL direct_parent
      ON direct_parent.PROTOCOL_ID = location.PROTOCOL_ID
    WHERE NOT EXISTS (
        SELECT 1
        FROM KCOEUS.PROTOCOL exact_parent
        WHERE exact_parent.PROTOCOL_NUMBER = location.PROTOCOL_NUMBER
          AND exact_parent.SEQUENCE_NUMBER = location.SEQUENCE_NUMBER
    )
    ORDER BY
        location.PROTOCOL_NUMBER,
        location.SEQUENCE_NUMBER,
        location.PROTOCOL_LOCATION_ID
)
WHERE ROWNUM <= 25;

/*
 * 7. Installed foreign-key owner-chain evidence.
 *
 * The verified descriptor contains no owner identifier other than
 * PROTOCOL_ID. This metadata query proves whether BU installed another
 * physical FK path that must be considered.
 */
SELECT
    constraint_row.CONSTRAINT_NAME,
    column_row.COLUMN_NAME,
    parent_constraint.TABLE_NAME AS referenced_table,
    parent_column.COLUMN_NAME AS referenced_column
FROM ALL_CONSTRAINTS constraint_row
JOIN ALL_CONS_COLUMNS column_row
  ON column_row.OWNER = constraint_row.OWNER
 AND column_row.CONSTRAINT_NAME = constraint_row.CONSTRAINT_NAME
LEFT JOIN ALL_CONSTRAINTS parent_constraint
  ON parent_constraint.OWNER = constraint_row.R_OWNER
 AND parent_constraint.CONSTRAINT_NAME = constraint_row.R_CONSTRAINT_NAME
LEFT JOIN ALL_CONS_COLUMNS parent_column
  ON parent_column.OWNER = parent_constraint.OWNER
 AND parent_column.CONSTRAINT_NAME = parent_constraint.CONSTRAINT_NAME
 AND parent_column.POSITION = column_row.POSITION
WHERE constraint_row.OWNER = 'KCOEUS'
  AND constraint_row.TABLE_NAME = 'PROTOCOL_LOCATION'
  AND constraint_row.CONSTRAINT_TYPE = 'R'
ORDER BY constraint_row.CONSTRAINT_NAME, column_row.POSITION;
