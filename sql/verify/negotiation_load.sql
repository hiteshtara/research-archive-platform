-- PostgreSQL verification for a completed Negotiation archive load.

SELECT 'negotiation' AS table_name, COUNT(*) AS row_count
FROM archive.negotiation
UNION ALL
SELECT 'negotiation_activity', COUNT(*)
FROM archive.negotiation_activity
UNION ALL
SELECT 'negotiation_custom_data', COUNT(*)
FROM archive.negotiation_custom_data
UNION ALL
SELECT 'negotiation_notification', COUNT(*)
FROM archive.negotiation_notification
UNION ALL
SELECT 'negotiation_unassociated_detail', COUNT(*)
FROM archive.negotiation_unassociated_detail
ORDER BY table_name;

SELECT 'negotiation_activity' AS child_table, COUNT(*) AS orphan_count
FROM archive.negotiation_activity child
LEFT JOIN archive.negotiation parent
    ON parent.negotiation_id = child.negotiation_id
WHERE parent.negotiation_id IS NULL
UNION ALL
SELECT 'negotiation_custom_data', COUNT(*)
FROM archive.negotiation_custom_data child
LEFT JOIN archive.negotiation parent
    ON parent.negotiation_id = child.negotiation_id
WHERE parent.negotiation_id IS NULL
UNION ALL
SELECT 'negotiation_notification', COUNT(*)
FROM archive.negotiation_notification child
LEFT JOIN archive.negotiation parent
    ON parent.negotiation_id = child.owning_document_id_fk
WHERE parent.negotiation_id IS NULL
UNION ALL
SELECT 'negotiation_unassociated_detail', COUNT(*)
FROM archive.negotiation_unassociated_detail child
LEFT JOIN archive.negotiation parent
    ON parent.negotiation_id = child.negotiation_id
WHERE parent.negotiation_id IS NULL
ORDER BY child_table;

SELECT
    domain,
    status,
    rows_read,
    rows_loaded,
    rows_rejected,
    completed_at
FROM archive.load_run
WHERE domain = 'NEGOTIATION'
ORDER BY load_id DESC
LIMIT 5;

-- Run the loader twice with the same CSV set. This result should return two
-- rows with identical rows_read and rows_loaded values.
WITH latest_successful_loads AS (
    SELECT
        load_id,
        rows_read,
        rows_loaded,
        completed_at,
        ROW_NUMBER() OVER (ORDER BY load_id DESC) AS load_rank
    FROM archive.load_run
    WHERE domain = 'NEGOTIATION'
      AND status = 'LOADED'
)
SELECT
    load_id,
    rows_read,
    rows_loaded,
    completed_at
FROM latest_successful_loads
WHERE load_rank <= 2
ORDER BY load_rank;
