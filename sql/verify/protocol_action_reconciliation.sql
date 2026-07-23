-- Protocol Action PostgreSQL reconciliation. Read-only.

WITH evaluated AS (
    SELECT
        action.protocol_action_id,
        action.protocol_id,
        action.source_protocol_id,
        protocol.protocol_id AS archive_parent_id,
        (
            SELECT COUNT(*)
            FROM archive.protocol_version candidate
            WHERE candidate.protocol_number = action.protocol_number
              AND candidate.sequence_number = action.sequence_number
        ) AS tuple_parent_count
    FROM archive.protocol_action action
    LEFT JOIN archive.protocol_version protocol
      ON protocol.protocol_id = action.protocol_id
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
    COUNT(*) - COUNT(DISTINCT protocol_action_id)
        AS duplicate_source_identifiers,
    COUNT(*) FILTER (WHERE archive_parent_id IS NULL)
        AS archive_orphan_count
FROM evaluated;

SELECT
    COUNT(*) FILTER (WHERE protocol_action_type_code IS NULL)
        AS null_action_type_codes,
    COUNT(*) FILTER (WHERE action_date IS NULL)
        AS null_action_dates,
    COUNT(*) FILTER (WHERE actual_action_date IS NULL)
        AS null_actual_action_dates,
    COUNT(*) FILTER (WHERE submission_id_fk IS NULL)
        AS null_submission_references,
    COUNT(*) FILTER (WHERE source_update_timestamp IS NULL)
        AS null_update_timestamps,
    COUNT(*) FILTER (WHERE source_update_user IS NULL)
        AS null_update_users,
    COUNT(*) FILTER (WHERE source_version_number IS NULL)
        AS null_version_numbers,
    COUNT(*) FILTER (WHERE source_object_id IS NULL)
        AS null_object_ids
FROM archive.protocol_action;
