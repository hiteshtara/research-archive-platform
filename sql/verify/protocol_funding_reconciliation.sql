-- Protocol Funding reconciliation. Read-only.

SELECT
    COUNT(*) AS total_rows,
    COUNT(DISTINCT protocol_funding_source_id)
        AS distinct_funding_source_ids,
    COUNT(*) - COUNT(DISTINCT protocol_funding_source_id)
        AS duplicate_funding_source_ids
FROM archive.protocol_funding;

SELECT
    COUNT(*) AS total_rows,
    COUNT(*) FILTER (WHERE parent_match_count = 1)
        AS resolved_parents,
    COUNT(*) FILTER (WHERE parent_match_count = 0)
        AS missing_parents,
    COUNT(*) FILTER (WHERE parent_match_count > 1)
        AS ambiguous_parents
FROM (
    SELECT
        funding.protocol_funding_source_id,
        (
            SELECT COUNT(*)
            FROM archive.protocol_version candidate
            WHERE candidate.protocol_number =
                  funding.protocol_number
              AND candidate.sequence_number =
                  funding.sequence_number
        ) AS parent_match_count
    FROM archive.protocol_funding funding
) resolution;

SELECT
    COUNT(*) AS source_protocol_id_mismatches
FROM archive.protocol_funding
WHERE source_protocol_id IS DISTINCT FROM protocol_id;

SELECT
    COUNT(*) FILTER (
        WHERE funding.protocol_number IS DISTINCT FROM
              protocol.protocol_number
    ) AS protocol_number_mismatches,
    COUNT(*) FILTER (
        WHERE funding.sequence_number IS DISTINCT FROM
              protocol.sequence_number
    ) AS protocol_sequence_mismatches
FROM archive.protocol_funding funding
JOIN archive.protocol_version protocol
  ON protocol.protocol_id = funding.protocol_id;

SELECT
    COUNT(*) FILTER (
        WHERE funding_source_type_code IS NULL
    ) AS null_funding_type_codes,
    COUNT(*) FILTER (
        WHERE funding_source_number IS NULL
          AND funding_source_name IS NULL
    ) AS missing_funding_values
FROM archive.protocol_funding;
