-- Protocol Research Area reconciliation. Read-only.

SELECT
    COUNT(*) AS total_archive_rows,
    COUNT(DISTINCT protocol_research_area_id)
        AS distinct_source_identifiers,
    COUNT(*) - COUNT(DISTINCT protocol_research_area_id)
        AS duplicate_source_identifiers
FROM archive.protocol_research_area;

SELECT
    COUNT(*) FILTER (WHERE parent_match_count = 1)
        AS resolved_parents,
    COUNT(*) FILTER (WHERE parent_match_count = 0)
        AS missing_parents,
    COUNT(*) FILTER (WHERE parent_match_count > 1)
        AS ambiguous_parents
FROM (
    SELECT
        area.protocol_research_area_id,
        (
            SELECT COUNT(*)
            FROM archive.protocol_version candidate
            WHERE candidate.protocol_number = area.protocol_number
              AND candidate.sequence_number = area.sequence_number
        ) AS parent_match_count
    FROM archive.protocol_research_area area
) resolution;

SELECT
    COUNT(*) AS source_protocol_id_mismatches
FROM archive.protocol_research_area
WHERE source_protocol_id IS DISTINCT FROM protocol_id;

SELECT
    COUNT(*) FILTER (
        WHERE area.protocol_number IS DISTINCT FROM protocol.protocol_number
           OR area.sequence_number IS DISTINCT FROM protocol.sequence_number
    ) AS protocol_mismatches,
    COUNT(*) FILTER (
        WHERE area.research_area_code IS NULL
           OR BTRIM(area.research_area_code) = ''
    ) AS research_area_value_mismatches
FROM archive.protocol_research_area area
JOIN archive.protocol_version protocol
  ON protocol.protocol_id = area.protocol_id;

SELECT COUNT(*) AS archive_orphan_count
FROM archive.protocol_research_area area
LEFT JOIN archive.protocol_version protocol
  ON protocol.protocol_id = area.protocol_id
WHERE protocol.protocol_id IS NULL;
