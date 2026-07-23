-- Protocol Submission PostgreSQL verification. Read-only.

SELECT
    COUNT(*) AS total_archived_rows,
    COUNT(DISTINCT submission_id) AS distinct_source_identifiers,
    COUNT(*) - COUNT(DISTINCT submission_id)
        AS duplicate_source_identifiers,
    COUNT(*) FILTER (WHERE protocol.protocol_id IS NOT NULL)
        AS resolved_parents,
    COUNT(*) FILTER (WHERE protocol.protocol_id IS NULL)
        AS missing_parents,
    COUNT(*) FILTER (
        WHERE submission.source_protocol_id IS DISTINCT FROM
              submission.protocol_id
    ) AS direct_id_mismatches,
    COUNT(*) FILTER (WHERE protocol.protocol_id IS NULL)
        AS archive_orphan_count
FROM archive.protocol_submission submission
LEFT JOIN archive.protocol_version protocol
  ON protocol.protocol_id = submission.protocol_id;

SELECT
    COUNT(*) FILTER (WHERE submission_number IS NULL)
        AS null_submission_numbers,
    COUNT(*) FILTER (WHERE submission_type_code IS NULL)
        AS null_submission_types,
    COUNT(*) FILTER (WHERE submission_status_code IS NULL)
        AS null_submission_statuses,
    COUNT(*) FILTER (WHERE submission_date IS NULL)
        AS null_submission_dates,
    COUNT(*) FILTER (WHERE source_update_timestamp IS NULL)
        AS null_update_timestamps,
    COUNT(*) FILTER (WHERE source_update_user IS NULL)
        AS null_update_users,
    COUNT(*) FILTER (WHERE source_version_number IS NULL)
        AS null_version_numbers,
    COUNT(*) FILTER (WHERE source_object_id IS NULL)
        AS null_object_ids
FROM archive.protocol_submission;

/*
 * Compare the following archive columns to the approved Oracle/CSV source:
 * submission_number, submission_type_code, submission_status_code,
 * submission_date. Expected mismatch count for each is zero.
 */
