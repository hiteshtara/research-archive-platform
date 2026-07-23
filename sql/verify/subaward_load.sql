-- PostgreSQL verification for a completed Subaward archive load.

SELECT 'subaward' AS table_name, COUNT(*) AS row_count
FROM archive.subaward
UNION ALL
SELECT 'subaward_amount', COUNT(*) FROM archive.subaward_amount
UNION ALL
SELECT 'subaward_attachment', COUNT(*) FROM archive.subaward_attachment
UNION ALL
SELECT 'subaward_closeout', COUNT(*) FROM archive.subaward_closeout
UNION ALL
SELECT 'subaward_contact', COUNT(*) FROM archive.subaward_contact
UNION ALL
SELECT 'subaward_custom_data', COUNT(*) FROM archive.subaward_custom_data
UNION ALL
SELECT 'subaward_funding', COUNT(*) FROM archive.subaward_funding
UNION ALL
SELECT 'subaward_notepad', COUNT(*) FROM archive.subaward_notepad
UNION ALL
SELECT 'subaward_notification', COUNT(*) FROM archive.subaward_notification
UNION ALL
SELECT 'subaward_report', COUNT(*) FROM archive.subaward_report
UNION ALL
SELECT 'subaward_template_info', COUNT(*)
FROM archive.subaward_template_info
ORDER BY table_name;

SELECT 'subaward_amount' AS child_table, COUNT(*) AS orphan_count
FROM archive.subaward_amount child
LEFT JOIN archive.subaward parent
    ON parent.subaward_id = child.subaward_id
WHERE parent.subaward_id IS NULL
UNION ALL
SELECT 'subaward_attachment', COUNT(*)
FROM archive.subaward_attachment child
LEFT JOIN archive.subaward parent
    ON parent.subaward_id = child.subaward_id
WHERE parent.subaward_id IS NULL
UNION ALL
SELECT 'subaward_closeout', COUNT(*)
FROM archive.subaward_closeout child
LEFT JOIN archive.subaward parent
    ON parent.subaward_id = child.subaward_id
WHERE parent.subaward_id IS NULL
UNION ALL
SELECT 'subaward_contact', COUNT(*)
FROM archive.subaward_contact child
LEFT JOIN archive.subaward parent
    ON parent.subaward_id = child.subaward_id
WHERE parent.subaward_id IS NULL
UNION ALL
SELECT 'subaward_custom_data', COUNT(*)
FROM archive.subaward_custom_data child
LEFT JOIN archive.subaward parent
    ON parent.subaward_id = child.subaward_id
WHERE parent.subaward_id IS NULL
UNION ALL
SELECT 'subaward_funding', COUNT(*)
FROM archive.subaward_funding child
LEFT JOIN archive.subaward parent
    ON parent.subaward_id = child.subaward_id
WHERE parent.subaward_id IS NULL
UNION ALL
SELECT 'subaward_notepad', COUNT(*)
FROM archive.subaward_notepad child
LEFT JOIN archive.subaward parent
    ON parent.subaward_id = child.subaward_id
WHERE parent.subaward_id IS NULL
UNION ALL
SELECT 'subaward_notification', COUNT(*)
FROM archive.subaward_notification child
LEFT JOIN archive.subaward parent
    ON parent.subaward_id = child.owning_document_id_fk
WHERE parent.subaward_id IS NULL
UNION ALL
SELECT 'subaward_report', COUNT(*)
FROM archive.subaward_report child
LEFT JOIN archive.subaward parent
    ON parent.subaward_id = child.subaward_id
WHERE parent.subaward_id IS NULL
UNION ALL
SELECT 'subaward_template_info', COUNT(*)
FROM archive.subaward_template_info child
LEFT JOIN archive.subaward parent
    ON parent.subaward_id = child.subaward_id
WHERE parent.subaward_id IS NULL
ORDER BY child_table;

SELECT subaward_id, COUNT(*) AS duplicate_count
FROM archive.subaward
GROUP BY subaward_id
HAVING COUNT(*) > 1
ORDER BY subaward_id;

-- Multiple physical Oracle rows can legitimately share a family/sequence.
-- Report them for review; do not treat this result as a primary-key failure.
SELECT subaward_code, sequence_number, COUNT(*) AS duplicate_count
FROM archive.subaward
GROUP BY subaward_code, sequence_number
HAVING COUNT(*) > 1
ORDER BY subaward_code, sequence_number;

SELECT 'subaward_amount' AS child_table, COUNT(*) AS inconsistent_rows
FROM archive.subaward_amount child
JOIN archive.subaward parent ON parent.subaward_id = child.subaward_id
WHERE child.subaward_code IS DISTINCT FROM parent.subaward_code
   OR child.sequence_number IS DISTINCT FROM parent.sequence_number
UNION ALL
SELECT 'subaward_attachment', COUNT(*)
FROM archive.subaward_attachment child
JOIN archive.subaward parent ON parent.subaward_id = child.subaward_id
WHERE child.subaward_code IS DISTINCT FROM parent.subaward_code
   OR child.sequence_number IS DISTINCT FROM parent.sequence_number
UNION ALL
SELECT 'subaward_closeout', COUNT(*)
FROM archive.subaward_closeout child
JOIN archive.subaward parent ON parent.subaward_id = child.subaward_id
WHERE child.subaward_code IS DISTINCT FROM parent.subaward_code
   OR child.sequence_number IS DISTINCT FROM parent.sequence_number
UNION ALL
SELECT 'subaward_contact', COUNT(*)
FROM archive.subaward_contact child
JOIN archive.subaward parent ON parent.subaward_id = child.subaward_id
WHERE child.subaward_code IS DISTINCT FROM parent.subaward_code
   OR child.sequence_number IS DISTINCT FROM parent.sequence_number
UNION ALL
SELECT 'subaward_custom_data', COUNT(*)
FROM archive.subaward_custom_data child
JOIN archive.subaward parent ON parent.subaward_id = child.subaward_id
WHERE child.subaward_code IS DISTINCT FROM parent.subaward_code
   OR child.sequence_number IS DISTINCT FROM parent.sequence_number
UNION ALL
SELECT 'subaward_funding', COUNT(*)
FROM archive.subaward_funding child
JOIN archive.subaward parent ON parent.subaward_id = child.subaward_id
WHERE child.subaward_code IS DISTINCT FROM parent.subaward_code
   OR child.sequence_number IS DISTINCT FROM parent.sequence_number
UNION ALL
SELECT 'subaward_report', COUNT(*)
FROM archive.subaward_report child
JOIN archive.subaward parent ON parent.subaward_id = child.subaward_id
WHERE child.subaward_code IS DISTINCT FROM parent.subaward_code
   OR child.sequence_number IS DISTINCT FROM parent.sequence_number
UNION ALL
SELECT 'subaward_template_info', COUNT(*)
FROM archive.subaward_template_info child
JOIN archive.subaward parent ON parent.subaward_id = child.subaward_id
WHERE child.subaward_code IS DISTINCT FROM parent.subaward_code
   OR child.sequence_number IS DISTINCT FROM parent.sequence_number
UNION ALL
SELECT 'subaward_notepad', COUNT(*)
FROM archive.subaward_notepad child
JOIN archive.subaward parent ON parent.subaward_id = child.subaward_id
WHERE child.subaward_code IS DISTINCT FROM parent.subaward_code
UNION ALL
SELECT 'subaward_notification', COUNT(*)
FROM archive.subaward_notification child
JOIN archive.subaward parent
    ON parent.subaward_id = child.owning_document_id_fk
WHERE child.subaward_code IS DISTINCT FROM parent.subaward_code
   OR child.document_number IS DISTINCT FROM parent.document_number
ORDER BY child_table;

-- These four source datasets are valid empty datasets and should remain empty
-- after loading the currently validated CSV export set.
SELECT 'subaward_closeout' AS expected_empty_table, COUNT(*) AS row_count
FROM archive.subaward_closeout
UNION ALL
SELECT 'subaward_report', COUNT(*) FROM archive.subaward_report
UNION ALL
SELECT 'subaward_notepad', COUNT(*) FROM archive.subaward_notepad
UNION ALL
SELECT 'subaward_notification', COUNT(*) FROM archive.subaward_notification
ORDER BY expected_empty_table;

SELECT
    domain,
    status,
    rows_read,
    rows_staged,
    rows_loaded,
    rows_rejected,
    completed_at
FROM archive.load_run
WHERE domain = 'SUBAWARD'
ORDER BY load_id DESC
LIMIT 5;

-- Run the loader twice with the same CSV set. The latest two successful rows
-- should have identical rows_read and rows_loaded values, while the table
-- counts above remain unchanged.
WITH latest_successful_loads AS (
    SELECT
        load_id,
        rows_read,
        rows_loaded,
        completed_at,
        ROW_NUMBER() OVER (ORDER BY load_id DESC) AS load_rank
    FROM archive.load_run
    WHERE domain = 'SUBAWARD'
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
