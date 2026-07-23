/*
 * Protocol Core reconciliation
 *
 * Compares the temporary flat Excel-based history table created by V007 with
 * the canonical CSV/Oracle Protocol Core table created by V021.
 *
 * Read-only. A passing reconciliation has zero unexplained rows missing from
 * either side and zero unexplained field mismatches.
 */

-- 1. Source row totals and distinct physical identifiers.
SELECT
    'legacy_irb_protocol_version' AS source_name,
    COUNT(*) AS row_count,
    COUNT(DISTINCT protocol_id) AS distinct_protocol_id_count
FROM archive.irb_protocol_version
UNION ALL
SELECT
    'protocol_version' AS source_name,
    COUNT(*) AS row_count,
    COUNT(DISTINCT protocol_id) AS distinct_protocol_id_count
FROM archive.protocol_version
ORDER BY source_name;

-- 2. Overall reconciliation result, matched by physical Oracle PROTOCOL_ID.
WITH comparison AS (
    SELECT
        legacy.protocol_id AS legacy_protocol_id,
        canonical.protocol_id AS canonical_protocol_id,
        legacy.protocol_number AS legacy_protocol_number,
        canonical.protocol_number AS canonical_protocol_number,
        legacy.sequence_number AS legacy_sequence_number,
        canonical.sequence_number AS canonical_sequence_number,
        legacy.protocol_status_code AS legacy_status_code,
        canonical.protocol_status_code AS canonical_status_code,
        legacy.protocol_status AS legacy_status,
        canonical.protocol_status_description AS canonical_status,
        legacy.title AS legacy_title,
        canonical.title AS canonical_title,
        legacy.approval_date AS legacy_approval_date,
        canonical.approval_date AS canonical_approval_date,
        legacy.expiration_date AS legacy_expiration_date,
        canonical.expiration_date AS canonical_expiration_date
    FROM archive.irb_protocol_version legacy
    FULL OUTER JOIN archive.protocol_version canonical
        ON canonical.protocol_id = legacy.protocol_id
)
SELECT
    COUNT(*) FILTER (
        WHERE legacy_protocol_id IS NULL
    ) AS missing_from_legacy_count,
    COUNT(*) FILTER (
        WHERE canonical_protocol_id IS NULL
    ) AS missing_from_canonical_count,
    COUNT(*) FILTER (
        WHERE legacy_protocol_id IS NOT NULL
          AND canonical_protocol_id IS NOT NULL
    ) AS matched_protocol_id_count,
    COUNT(*) FILTER (
        WHERE legacy_protocol_id IS NOT NULL
          AND canonical_protocol_id IS NOT NULL
          AND (
              legacy_protocol_number IS DISTINCT FROM
                  canonical_protocol_number
              OR legacy_sequence_number IS DISTINCT FROM
                  canonical_sequence_number
              OR legacy_status_code IS DISTINCT FROM canonical_status_code
              OR legacy_status IS DISTINCT FROM canonical_status
              OR legacy_title IS DISTINCT FROM canonical_title
              OR legacy_approval_date IS DISTINCT FROM
                  canonical_approval_date
              OR legacy_expiration_date IS DISTINCT FROM
                  canonical_expiration_date
          )
    ) AS field_mismatch_count
FROM comparison;

-- 3. Field-level mismatch counts for matched physical rows.
SELECT
    COUNT(*) AS matched_protocol_id_count,
    COUNT(*) FILTER (
        WHERE legacy.protocol_number IS DISTINCT FROM
            canonical.protocol_number
    ) AS protocol_number_mismatch_count,
    COUNT(*) FILTER (
        WHERE legacy.sequence_number IS DISTINCT FROM
            canonical.sequence_number
    ) AS sequence_number_mismatch_count,
    COUNT(*) FILTER (
        WHERE legacy.protocol_status_code IS DISTINCT FROM
            canonical.protocol_status_code
    ) AS status_code_mismatch_count,
    COUNT(*) FILTER (
        WHERE legacy.protocol_status IS DISTINCT FROM
            canonical.protocol_status_description
    ) AS status_description_mismatch_count,
    COUNT(*) FILTER (
        WHERE legacy.title IS DISTINCT FROM canonical.title
    ) AS title_mismatch_count,
    COUNT(*) FILTER (
        WHERE legacy.approval_date IS DISTINCT FROM
            canonical.approval_date
    ) AS approval_date_mismatch_count,
    COUNT(*) FILTER (
        WHERE legacy.expiration_date IS DISTINCT FROM
            canonical.expiration_date
    ) AS expiration_date_mismatch_count
FROM archive.irb_protocol_version legacy
JOIN archive.protocol_version canonical
    ON canonical.protocol_id = legacy.protocol_id;

-- 4. Detailed differences. An empty result means all compared rows match.
SELECT
    COALESCE(canonical.protocol_id, legacy.protocol_id) AS protocol_id,
    CASE
        WHEN legacy.protocol_id IS NULL THEN 'MISSING_FROM_LEGACY'
        WHEN canonical.protocol_id IS NULL THEN 'MISSING_FROM_CANONICAL'
        ELSE 'FIELD_MISMATCH'
    END AS reconciliation_status,
    legacy.protocol_number AS legacy_protocol_number,
    canonical.protocol_number AS canonical_protocol_number,
    legacy.sequence_number AS legacy_sequence_number,
    canonical.sequence_number AS canonical_sequence_number,
    legacy.protocol_status_code AS legacy_status_code,
    canonical.protocol_status_code AS canonical_status_code,
    legacy.protocol_status AS legacy_status,
    canonical.protocol_status_description AS canonical_status,
    legacy.title AS legacy_title,
    canonical.title AS canonical_title,
    legacy.approval_date AS legacy_approval_date,
    canonical.approval_date AS canonical_approval_date,
    legacy.expiration_date AS legacy_expiration_date,
    canonical.expiration_date AS canonical_expiration_date
FROM archive.irb_protocol_version legacy
FULL OUTER JOIN archive.protocol_version canonical
    ON canonical.protocol_id = legacy.protocol_id
WHERE legacy.protocol_id IS NULL
   OR canonical.protocol_id IS NULL
   OR legacy.protocol_number IS DISTINCT FROM canonical.protocol_number
   OR legacy.sequence_number IS DISTINCT FROM canonical.sequence_number
   OR legacy.protocol_status_code IS DISTINCT FROM
        canonical.protocol_status_code
   OR legacy.protocol_status IS DISTINCT FROM
        canonical.protocol_status_description
   OR legacy.title IS DISTINCT FROM canonical.title
   OR legacy.approval_date IS DISTINCT FROM canonical.approval_date
   OR legacy.expiration_date IS DISTINCT FROM canonical.expiration_date
ORDER BY
    COALESCE(canonical.protocol_number, legacy.protocol_number),
    COALESCE(canonical.sequence_number, legacy.sequence_number),
    COALESCE(canonical.protocol_id, legacy.protocol_id);
