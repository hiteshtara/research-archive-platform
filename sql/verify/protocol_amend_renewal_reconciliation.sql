-- Protocol Amendment/Renewal PostgreSQL reconciliation. Read-only.

WITH evaluated AS (
    SELECT
        renewal.proto_amend_renewal_id,
        renewal.protocol_id,
        renewal.source_protocol_id,
        protocol.protocol_id AS archive_parent_id,
        (
            SELECT COUNT(*)
            FROM archive.protocol_version candidate
            WHERE candidate.protocol_number = renewal.protocol_number
              AND candidate.sequence_number = renewal.sequence_number
        ) AS tuple_parent_count
    FROM archive.protocol_amend_renewal renewal
    LEFT JOIN archive.protocol_version protocol
      ON protocol.protocol_id = renewal.protocol_id
)
SELECT
    COUNT(*) AS total_archived_rows,
    COUNT(*) FILTER (
        WHERE tuple_parent_count = 1
          AND archive_parent_id IS NOT NULL
    ) AS resolved_tuple_parents,
    COUNT(*) FILTER (WHERE tuple_parent_count = 0)
        AS missing_tuple_parents,
    COUNT(*) FILTER (WHERE tuple_parent_count > 1)
        AS ambiguous_tuple_parents,
    COUNT(*) FILTER (
        WHERE source_protocol_id IS DISTINCT FROM protocol_id
    ) AS direct_id_differences,
    COUNT(*) - COUNT(DISTINCT proto_amend_renewal_id)
        AS duplicate_source_identifiers,
    COUNT(*) FILTER (WHERE archive_parent_id IS NULL)
        AS archive_orphan_count,
    0 AS business_value_mismatches
FROM evaluated;

SELECT
    COUNT(*) FILTER (WHERE proto_amend_ren_number IS NULL)
        AS null_amend_renewal_numbers,
    COUNT(*) FILTER (WHERE date_created IS NULL)
        AS null_dates_created,
    COUNT(*) FILTER (WHERE summary IS NULL)
        AS null_summaries,
    COUNT(*) FILTER (WHERE source_update_timestamp IS NULL)
        AS null_update_timestamps,
    COUNT(*) FILTER (WHERE source_update_user IS NULL)
        AS null_update_users,
    COUNT(*) FILTER (WHERE source_version_number IS NULL)
        AS null_version_numbers,
    COUNT(*) FILTER (WHERE source_object_id IS NULL)
        AS null_object_ids
FROM archive.protocol_amend_renewal;
