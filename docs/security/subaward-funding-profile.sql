-- Subaward funding-link profile, for the approved Subaward authorization rule
-- ("a user may access a Subaward when authorized for at least ONE of its
-- funding Awards").
--
-- READ ONLY. Every statement is a SELECT. Nothing here writes, locks or
-- creates anything, so it is safe to run as archive_analyst.
--
-- HOW TO RUN (the approved path - see docs/runbooks/CLOUDSHELL_DATABASE_ACCESS.md):
--   1. buaws, confirm account 770203350335
--   2. AWS console -> CloudShell -> VPC environment research-archive-analysis
--   3. scripts/mac-show-analyst-password.sh on the Mac
--   4. psql "host=...rds.amazonaws.com port=5432 dbname=research_archive
--           user=archive_analyst sslmode=require" --password
--   5. \i this file, or paste section by section
--
-- There is no supported Mac-to-RDS route, so this cannot be run from a
-- laptop shell. Output is counts and de-identified shapes only: no titles,
-- no sponsor names, no people. Award and Subaward identifiers are hashed
-- where examples are shown.

\pset pager off
\timing on

-- =====================================================================
-- Q1. Total Subaward records, and distinct Subaward families.
--
-- Grain note, per the project's own rules: archive.subaward is VERSION
-- grained (subaward_id is the PK, subaward_code + sequence_number is the
-- version key). The business-object count is DISTINCT subaward_code.
-- =====================================================================
SELECT
    COUNT(*)                        AS subaward_version_rows,
    COUNT(DISTINCT subaward_code)   AS subaward_families
FROM archive.subaward;

-- =====================================================================
-- Q2/Q3/Q4/Q5. Funding-Award coverage, per Subaward FAMILY.
--
-- Counted per family rather than per version because authorization is
-- asked of a record a user opens, and the UI opens families. Q10 below
-- checks whether the per-version answer differs.
-- =====================================================================
WITH per_family AS (
    SELECT
        s.subaward_code,
        COUNT(DISTINCT sf.award_id) AS distinct_funding_awards
    FROM archive.subaward s
    LEFT JOIN archive.subaward_funding sf
           ON sf.subaward_id = s.subaward_id
          AND sf.award_id IS NOT NULL
    GROUP BY s.subaward_code
)
SELECT
    COUNT(*)                                                   AS families_total,
    COUNT(*) FILTER (WHERE distinct_funding_awards >= 1)       AS with_at_least_one,
    ROUND(100.0 * COUNT(*) FILTER (WHERE distinct_funding_awards >= 1)
          / NULLIF(COUNT(*), 0), 2)                            AS pct_with_at_least_one,
    COUNT(*) FILTER (WHERE distinct_funding_awards = 0)        AS with_none,
    ROUND(100.0 * COUNT(*) FILTER (WHERE distinct_funding_awards = 0)
          / NULLIF(COUNT(*), 0), 2)                            AS pct_with_none,
    COUNT(*) FILTER (WHERE distinct_funding_awards = 1)        AS with_exactly_one,
    COUNT(*) FILTER (WHERE distinct_funding_awards > 1)        AS with_multiple,
    MAX(distinct_funding_awards)                               AS max_funding_awards
FROM per_family;

-- =====================================================================
-- Q6. Funding Award references that do NOT resolve to an archived Award.
--
-- subaward_funding.award_id has NO foreign key to archive.award_version,
-- so dangling references are possible and must not be treated as
-- authorization. This is the number the rule's "broken reference" case
-- is about.
-- =====================================================================
SELECT
    COUNT(*)                                        AS funding_rows_with_award_id,
    COUNT(*) FILTER (WHERE av.award_id IS NULL)     AS unresolvable_rows,
    ROUND(100.0 * COUNT(*) FILTER (WHERE av.award_id IS NULL)
          / NULLIF(COUNT(*), 0), 2)                 AS pct_unresolvable,
    COUNT(DISTINCT sf.subaward_code)
        FILTER (WHERE av.award_id IS NULL)          AS families_touched_by_unresolvable
FROM archive.subaward_funding sf
LEFT JOIN archive.award_version av ON av.award_id = sf.award_id
WHERE sf.award_id IS NOT NULL;

-- Families whose ONLY funding references are unresolvable: these behave
-- exactly like "no funding Award" under the approved rule, and are the
-- population most likely to surprise someone.
WITH resolution AS (
    SELECT
        s.subaward_code,
        COUNT(*) FILTER (WHERE sf.award_id IS NOT NULL)                   AS refs,
        COUNT(*) FILTER (WHERE sf.award_id IS NOT NULL
                           AND av.award_id IS NOT NULL)                   AS resolvable
    FROM archive.subaward s
    LEFT JOIN archive.subaward_funding sf ON sf.subaward_id = s.subaward_id
    LEFT JOIN archive.award_version av    ON av.award_id = sf.award_id
    GROUP BY s.subaward_code
)
SELECT
    COUNT(*) FILTER (WHERE refs > 0 AND resolvable = 0) AS families_all_refs_broken,
    COUNT(*) FILTER (WHERE refs = 0)                    AS families_no_refs_at_all
FROM resolution;

-- =====================================================================
-- Q7. Does award_id identify an Award VERSION or an Award FAMILY?
--
-- award_version.award_id is the version primary key, so the expectation
-- is "one version". This proves it rather than assuming: if every
-- award_id matches exactly one row, it is version-grained. The second
-- query shows how many DISTINCT award_numbers a Subaward family reaches,
-- which is what the predicate must actually scope against.
-- =====================================================================
SELECT
    COUNT(*)                                   AS distinct_award_ids_referenced,
    MIN(matches)                               AS min_award_version_rows_per_id,
    MAX(matches)                               AS max_award_version_rows_per_id
FROM (
    SELECT sf.award_id, COUNT(av.award_id) AS matches
    FROM (SELECT DISTINCT award_id FROM archive.subaward_funding WHERE award_id IS NOT NULL) sf
    LEFT JOIN archive.award_version av ON av.award_id = sf.award_id
    GROUP BY sf.award_id
) t;

-- How many distinct Award FAMILIES (award_number) one Subaward family
-- reaches. Under the ANY rule this is the set the user must intersect.
WITH fam AS (
    SELECT s.subaward_code, COUNT(DISTINCT av.award_number) AS award_families
    FROM archive.subaward s
    JOIN archive.subaward_funding sf ON sf.subaward_id = s.subaward_id
    JOIN archive.award_version av    ON av.award_id = sf.award_id
    GROUP BY s.subaward_code
)
SELECT
    COUNT(*)                                        AS subaward_families_with_resolvable_funding,
    COUNT(*) FILTER (WHERE award_families = 1)      AS reach_one_award_family,
    COUNT(*) FILTER (WHERE award_families > 1)      AS reach_multiple_award_families,
    MAX(award_families)                             AS max_award_families
FROM fam;

-- =====================================================================
-- Q8/Q9. requisitioner_unit against archive.unit.
--
-- RECORDED FOR EVIDENCE ONLY. The approved rule does NOT use this column,
-- and nothing should be built on it without a separate decision. These
-- numbers exist so that decision, if it is ever taken, starts from data.
--
-- Normalisation is deliberately conservative: trim and upper only. No
-- zero-padding, no prefix stripping, no fuzzy matching - inventing a
-- normalisation is how a unit key silently starts matching the wrong
-- department.
-- =====================================================================
WITH norm AS (
    SELECT
        s.subaward_code,
        NULLIF(UPPER(TRIM(s.requisitioner_unit)), '') AS unit_norm
    FROM archive.subaward s
)
SELECT
    COUNT(*)                                                       AS subaward_rows,
    COUNT(*) FILTER (WHERE unit_norm IS NULL)                      AS null_or_blank_unit,
    COUNT(*) FILTER (WHERE unit_norm IS NOT NULL AND u.unit_number IS NOT NULL)
                                                                   AS matched_unit,
    COUNT(*) FILTER (WHERE unit_norm IS NOT NULL AND u.unit_number IS NULL)
                                                                   AS unmatched_unit,
    ROUND(100.0 * COUNT(*) FILTER (WHERE unit_norm IS NOT NULL AND u.unit_number IS NOT NULL)
          / NULLIF(COUNT(*), 0), 2)                                AS pct_matched
FROM norm
LEFT JOIN archive.unit u ON UPPER(TRIM(u.unit_number)) = norm.unit_norm;

-- Ambiguity check: does one normalised value hit more than one unit row?
SELECT COUNT(*) AS normalised_unit_values_matching_multiple_units
FROM (
    SELECT UPPER(TRIM(u.unit_number)) AS k, COUNT(*) AS n
    FROM archive.unit u
    GROUP BY 1
    HAVING COUNT(*) > 1
) t;

-- Shape of the unmatched values, de-identified: length and character
-- class only, never the value itself.
SELECT
    LENGTH(unit_norm)                                        AS value_length,
    unit_norm ~ '^[0-9]+$'                                   AS all_digits,
    COUNT(*)                                                 AS occurrences
FROM (
    SELECT NULLIF(UPPER(TRIM(s.requisitioner_unit)), '') AS unit_norm
    FROM archive.subaward s
) n
LEFT JOIN archive.unit u ON UPPER(TRIM(u.unit_number)) = n.unit_norm
WHERE n.unit_norm IS NOT NULL AND u.unit_number IS NULL
GROUP BY 1, 2
ORDER BY occurrences DESC
LIMIT 20;

-- =====================================================================
-- Q10. Do funding relationships vary ACROSS versions of one Subaward?
--
-- This decides whether the predicate may be evaluated family-wide or has
-- to be per version. If funding sets differ between versions, a
-- family-wide ANY rule grants access through a version the user may not
-- otherwise reach - which would be a business question, not a technical
-- one.
-- =====================================================================
WITH per_version AS (
    SELECT
        s.subaward_code,
        s.sequence_number,
        COALESCE(STRING_AGG(DISTINCT sf.award_id::TEXT, ',' ORDER BY sf.award_id::TEXT), '') AS funding_set
    FROM archive.subaward s
    LEFT JOIN archive.subaward_funding sf
           ON sf.subaward_id = s.subaward_id AND sf.award_id IS NOT NULL
    GROUP BY s.subaward_code, s.sequence_number
)
SELECT
    COUNT(DISTINCT subaward_code)                                          AS families,
    COUNT(DISTINCT subaward_code) FILTER (WHERE distinct_sets > 1)         AS families_whose_funding_varies_by_version,
    ROUND(100.0 * COUNT(DISTINCT subaward_code) FILTER (WHERE distinct_sets > 1)
          / NULLIF(COUNT(DISTINCT subaward_code), 0), 2)                   AS pct_varying
FROM (
    SELECT subaward_code, COUNT(DISTINCT funding_set) AS distinct_sets
    FROM per_version
    GROUP BY subaward_code
) t;

-- Representative anonymised examples of varying families: hashed code,
-- version numbers and funding-set CARDINALITY only. No identifiers leave
-- this query in readable form.
WITH per_version AS (
    SELECT
        s.subaward_code,
        s.sequence_number,
        COUNT(DISTINCT sf.award_id) AS funding_count
    FROM archive.subaward s
    LEFT JOIN archive.subaward_funding sf
           ON sf.subaward_id = s.subaward_id AND sf.award_id IS NOT NULL
    GROUP BY s.subaward_code, s.sequence_number
), varying AS (
    SELECT subaward_code
    FROM per_version
    GROUP BY subaward_code
    HAVING COUNT(DISTINCT funding_count) > 1
    LIMIT 5
)
SELECT
    LEFT(MD5(pv.subaward_code), 8) AS subaward_ref,
    pv.sequence_number,
    pv.funding_count
FROM per_version pv
JOIN varying v ON v.subaward_code = pv.subaward_code
ORDER BY subaward_ref, pv.sequence_number;
