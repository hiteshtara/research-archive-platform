-- Protocol Location reconciliation. Read-only.

SELECT
    COUNT(*) AS total_rows,
    COUNT(DISTINCT protocol_location_id) AS distinct_source_identifiers,
    COUNT(*) - COUNT(DISTINCT protocol_location_id)
        AS duplicate_source_identifiers
FROM archive.protocol_location;

SELECT
    COUNT(*) AS total_rows,
    COUNT(*) FILTER (
        WHERE parent_resolution_method = 'NUMBER_SEQUENCE'
    ) AS number_sequence_rows,
    COUNT(*) FILTER (
        WHERE parent_resolution_method = 'DIRECT_ID_PLACEHOLDER'
    ) AS direct_id_placeholder_rows,
    COUNT(DISTINCT source_protocol_id) FILTER (
        WHERE parent_resolution_method = 'DIRECT_ID_PLACEHOLDER'
    ) AS distinct_placeholder_direct_ids,
    COUNT(*) FILTER (
        WHERE parent_resolution_method = 'DIRECT_ID_PLACEHOLDER'
          AND NOT source_direct_parent_found
    ) AS missing_archive_direct_parents,
    0 AS rejected_rows,
    COUNT(*) FILTER (WHERE archive_parent_found) AS resolved_parents,
    COUNT(*) FILTER (
        WHERE NOT archive_parent_found
           OR (
                parent_resolution_method = 'NUMBER_SEQUENCE'
                AND parent_match_count = 0
              )
    ) AS missing_parents,
    COUNT(*) FILTER (
        WHERE parent_resolution_method = 'NUMBER_SEQUENCE'
          AND parent_match_count > 1
    ) AS ambiguous_parents
FROM (
    SELECT
        location.protocol_location_id,
        location.source_protocol_id,
        location.parent_resolution_method,
        archive_parent.protocol_id IS NOT NULL AS archive_parent_found,
        source_direct_parent.protocol_id IS NOT NULL
            AS source_direct_parent_found,
        (
            SELECT COUNT(*)
            FROM archive.protocol_version candidate
            WHERE candidate.protocol_number = location.protocol_number
              AND candidate.sequence_number = location.sequence_number
        ) AS parent_match_count
    FROM archive.protocol_location location
    LEFT JOIN archive.protocol_version archive_parent
      ON archive_parent.protocol_id = location.protocol_id
    LEFT JOIN archive.protocol_version source_direct_parent
      ON source_direct_parent.protocol_id = location.source_protocol_id
) resolution;

SELECT COUNT(*) AS source_protocol_id_mismatches
FROM archive.protocol_location
WHERE source_protocol_id IS DISTINCT FROM protocol_id;

SELECT
    COUNT(*) FILTER (
        WHERE (
                location.parent_resolution_method = 'NUMBER_SEQUENCE'
                AND (
                    location.protocol_number IS DISTINCT FROM
                    protocol.protocol_number
                    OR location.sequence_number IS DISTINCT FROM
                    protocol.sequence_number
                )
              )
           OR (
                location.parent_resolution_method =
                'DIRECT_ID_PLACEHOLDER'
                AND (
                    BTRIM(location.protocol_number) != '0'
                    OR location.sequence_number != 0
                )
              )
    ) AS protocol_mismatches,
    COUNT(*) FILTER (
        WHERE location.protocol_org_type_code IS NULL
          AND location.organization_id IS NULL
          AND location.rolodex_id IS NULL
    ) AS location_value_mismatches
FROM archive.protocol_location location
JOIN archive.protocol_version protocol
  ON protocol.protocol_id = location.protocol_id;

SELECT COUNT(*) AS archive_orphan_count
FROM archive.protocol_location location
LEFT JOIN archive.protocol_version protocol
  ON protocol.protocol_id = location.protocol_id
WHERE protocol.protocol_id IS NULL;
