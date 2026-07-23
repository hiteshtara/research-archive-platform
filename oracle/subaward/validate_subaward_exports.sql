-- Subaward Oracle export validation package.
-- Run in a read-only Oracle session. Each result set is intentionally separate.

-- ============================================================================
-- Installed constraints and indexes for all exported source tables
-- ============================================================================
SELECT
    ac.owner,
    ac.table_name,
    ac.constraint_name,
    ac.constraint_type,
    ac.status,
    ac.validated,
    acc.position,
    acc.column_name,
    ac.r_owner,
    ac.r_constraint_name
FROM ALL_CONSTRAINTS ac
LEFT JOIN ALL_CONS_COLUMNS acc
    ON acc.owner = ac.owner
   AND acc.constraint_name = ac.constraint_name
   AND acc.table_name = ac.table_name
WHERE ac.owner = 'KCOEUS'
  AND ac.table_name IN (
      'SUBAWARD', 'SUBAWARD_DOCUMENT', 'SUBAWARD_EXTENSION',
      'SUBAWARD_AMOUNT_INFO', 'SUBAWARD_CONTACT', 'SUBAWARD_CUSTOM_DATA',
      'SUBAWARD_FUNDING_SOURCE', 'SUBAWARD_ATTACHMENTS', 'SUBAWARD_CLOSEOUT',
      'SUBAWARD_REPORTS', 'SUBAWARD_NOTEPAD', 'SUBAWARD_NOTIFICATION',
      'SUBAWARD_TEMPLATE_INFO'
  )
ORDER BY ac.table_name, ac.constraint_type, ac.constraint_name, acc.position;

SELECT
    ai.owner,
    ai.table_name,
    ai.index_name,
    ai.uniqueness,
    ai.status,
    aic.column_position,
    aic.column_name,
    aic.descend
FROM ALL_INDEXES ai
JOIN ALL_IND_COLUMNS aic
    ON aic.index_owner = ai.owner
   AND aic.index_name = ai.index_name
   AND aic.table_owner = ai.table_owner
   AND aic.table_name = ai.table_name
WHERE ai.table_owner = 'KCOEUS'
  AND ai.table_name IN (
      'SUBAWARD', 'SUBAWARD_DOCUMENT', 'SUBAWARD_EXTENSION',
      'SUBAWARD_AMOUNT_INFO', 'SUBAWARD_CONTACT', 'SUBAWARD_CUSTOM_DATA',
      'SUBAWARD_FUNDING_SOURCE', 'SUBAWARD_ATTACHMENTS', 'SUBAWARD_CLOSEOUT',
      'SUBAWARD_REPORTS', 'SUBAWARD_NOTEPAD', 'SUBAWARD_NOTIFICATION',
      'SUBAWARD_TEMPLATE_INFO'
  )
ORDER BY ai.table_name, ai.index_name, aic.column_position;

-- ============================================================================
-- Subawards
-- ============================================================================
SELECT COUNT(*) AS total_rows FROM KCOEUS.SUBAWARD;

SELECT COUNT(DISTINCT s.subaward_id) AS distinct_primary_keys
FROM KCOEUS.SUBAWARD s;

SELECT s.subaward_id, COUNT(*) AS duplicate_count
FROM KCOEUS.SUBAWARD s
GROUP BY s.subaward_id
HAVING COUNT(*) > 1
ORDER BY s.subaward_id;

SELECT COUNT(*) AS null_primary_keys
FROM KCOEUS.SUBAWARD s
WHERE s.subaward_id IS NULL;

SELECT COUNT(*) AS orphan_document_numbers
FROM KCOEUS.SUBAWARD s
LEFT JOIN KCOEUS.SUBAWARD_DOCUMENT sd
    ON sd.document_number = s.document_number
WHERE s.document_number IS NOT NULL
  AND sd.document_number IS NULL;

SELECT COUNT(*) AS missing_status_lookup_rows
FROM KCOEUS.SUBAWARD s
LEFT JOIN KCOEUS.SUBAWARD_STATUS ss
    ON ss.subaward_status_code = s.status_code
WHERE s.status_code IS NOT NULL
  AND ss.subaward_status_code IS NULL;

SELECT
    MIN(s.start_date) AS min_start_date,
    MAX(s.start_date) AS max_start_date,
    MIN(s.end_date) AS min_end_date,
    MAX(s.end_date) AS max_end_date,
    MIN(s.closeout_date) AS min_closeout_date,
    MAX(s.closeout_date) AS max_closeout_date,
    MIN(s.date_of_fully_executed) AS min_fully_executed_date,
    MAX(s.date_of_fully_executed) AS max_fully_executed_date,
    MIN(s.update_timestamp) AS min_update_timestamp,
    MAX(s.update_timestamp) AS max_update_timestamp
FROM KCOEUS.SUBAWARD s;

SELECT
    SUM(CASE WHEN s.update_timestamp IS NULL THEN 1 ELSE 0 END) AS null_update_timestamp,
    SUM(CASE WHEN s.update_user IS NULL THEN 1 ELSE 0 END) AS null_update_user,
    SUM(CASE WHEN s.ver_nbr IS NULL THEN 1 ELSE 0 END) AS null_ver_nbr,
    SUM(CASE WHEN s.obj_id IS NULL THEN 1 ELSE 0 END) AS null_obj_id
FROM KCOEUS.SUBAWARD s;

SELECT
    ROUND(100 * SUM(CASE WHEN s.subaward_code IS NULL THEN 1 ELSE 0 END) / NULLIF(COUNT(*), 0), 2) AS subaward_code_null_pct,
    ROUND(100 * SUM(CASE WHEN s.sequence_number IS NULL THEN 1 ELSE 0 END) / NULLIF(COUNT(*), 0), 2) AS sequence_number_null_pct,
    ROUND(100 * SUM(CASE WHEN s.document_number IS NULL THEN 1 ELSE 0 END) / NULLIF(COUNT(*), 0), 2) AS document_number_null_pct,
    ROUND(100 * SUM(CASE WHEN s.title IS NULL THEN 1 ELSE 0 END) / NULLIF(COUNT(*), 0), 2) AS title_null_pct,
    ROUND(100 * SUM(CASE WHEN s.status_code IS NULL THEN 1 ELSE 0 END) / NULLIF(COUNT(*), 0), 2) AS status_code_null_pct,
    ROUND(100 * SUM(CASE WHEN s.organization_id IS NULL THEN 1 ELSE 0 END) / NULLIF(COUNT(*), 0), 2) AS organization_id_null_pct
FROM KCOEUS.SUBAWARD s;

SELECT s.*
FROM KCOEUS.SUBAWARD s
ORDER BY s.subaward_code, s.sequence_number, s.subaward_id
FETCH FIRST 10 ROWS ONLY;

SELECT
    COUNT(DISTINCT s.subaward_code) AS subaward_families,
    MIN(s.sequence_number) AS min_sequence_number,
    MAX(s.sequence_number) AS max_sequence_number,
    SUM(CASE WHEN s.sequence_number IS NULL THEN 1 ELSE 0 END) AS null_sequence_numbers
FROM KCOEUS.SUBAWARD s;

SELECT s.subaward_code, s.sequence_number, COUNT(*) AS duplicate_family_versions
FROM KCOEUS.SUBAWARD s
WHERE s.subaward_code IS NOT NULL
  AND s.sequence_number IS NOT NULL
GROUP BY s.subaward_code, s.sequence_number
HAVING COUNT(*) > 1
ORDER BY s.subaward_code, s.sequence_number;

SELECT
    s.document_number,
    COUNT(DISTINCT s.subaward_code) AS subaward_code_count,
    COUNT(*) AS version_count
FROM KCOEUS.SUBAWARD s
WHERE s.document_number IS NOT NULL
GROUP BY s.document_number
HAVING COUNT(DISTINCT s.subaward_code) > 1
ORDER BY s.document_number;

-- ============================================================================
-- Amounts
-- ============================================================================
SELECT COUNT(*) AS total_rows FROM KCOEUS.SUBAWARD_AMOUNT_INFO;

SELECT COUNT(DISTINCT sai.subaward_amount_info_id) AS distinct_primary_keys
FROM KCOEUS.SUBAWARD_AMOUNT_INFO sai;

SELECT sai.subaward_amount_info_id, COUNT(*) AS duplicate_count
FROM KCOEUS.SUBAWARD_AMOUNT_INFO sai
GROUP BY sai.subaward_amount_info_id
HAVING COUNT(*) > 1
ORDER BY sai.subaward_amount_info_id;

SELECT COUNT(*) AS null_primary_keys
FROM KCOEUS.SUBAWARD_AMOUNT_INFO sai
WHERE sai.subaward_amount_info_id IS NULL;

SELECT COUNT(*) AS orphan_parent_keys
FROM KCOEUS.SUBAWARD_AMOUNT_INFO sai
LEFT JOIN KCOEUS.SUBAWARD s ON s.subaward_id = sai.subaward_id
WHERE sai.subaward_id IS NOT NULL AND s.subaward_id IS NULL;

SELECT COUNT(*) AS missing_modification_type_rows
FROM KCOEUS.SUBAWARD_AMOUNT_INFO sai
LEFT JOIN KCOEUS.SUBAWARD_MODIFICATION_TYPE smt
    ON smt.code = sai.modification_type_code
WHERE sai.modification_type_code IS NOT NULL AND smt.code IS NULL;

SELECT
    MIN(sai.effective_date) AS min_effective_date,
    MAX(sai.effective_date) AS max_effective_date,
    MIN(sai.modification_effective_date) AS min_modification_date,
    MAX(sai.modification_effective_date) AS max_modification_date,
    MIN(sai.performance_start_date) AS min_performance_start_date,
    MAX(sai.performance_end_date) AS max_performance_end_date,
    MIN(sai.update_timestamp) AS min_update_timestamp,
    MAX(sai.update_timestamp) AS max_update_timestamp
FROM KCOEUS.SUBAWARD_AMOUNT_INFO sai;

SELECT
    SUM(CASE WHEN sai.update_timestamp IS NULL THEN 1 ELSE 0 END) AS null_update_timestamp,
    SUM(CASE WHEN sai.update_user IS NULL THEN 1 ELSE 0 END) AS null_update_user,
    SUM(CASE WHEN sai.ver_nbr IS NULL THEN 1 ELSE 0 END) AS null_ver_nbr,
    SUM(CASE WHEN sai.obj_id IS NULL THEN 1 ELSE 0 END) AS null_obj_id
FROM KCOEUS.SUBAWARD_AMOUNT_INFO sai;

SELECT
    ROUND(100 * SUM(CASE WHEN sai.subaward_id IS NULL THEN 1 ELSE 0 END) / NULLIF(COUNT(*), 0), 2) AS subaward_id_null_pct,
    ROUND(100 * SUM(CASE WHEN sai.subaward_code IS NULL THEN 1 ELSE 0 END) / NULLIF(COUNT(*), 0), 2) AS subaward_code_null_pct,
    ROUND(100 * SUM(CASE WHEN sai.sequence_number IS NULL THEN 1 ELSE 0 END) / NULLIF(COUNT(*), 0), 2) AS sequence_number_null_pct,
    ROUND(100 * SUM(CASE WHEN sai.obligated_amount IS NULL THEN 1 ELSE 0 END) / NULLIF(COUNT(*), 0), 2) AS obligated_amount_null_pct,
    ROUND(100 * SUM(CASE WHEN sai.anticipated_amount IS NULL THEN 1 ELSE 0 END) / NULLIF(COUNT(*), 0), 2) AS anticipated_amount_null_pct,
    ROUND(100 * SUM(CASE WHEN sai.file_data_id IS NULL THEN 1 ELSE 0 END) / NULLIF(COUNT(*), 0), 2) AS file_data_id_null_pct
FROM KCOEUS.SUBAWARD_AMOUNT_INFO sai;

SELECT sai.* FROM KCOEUS.SUBAWARD_AMOUNT_INFO sai
ORDER BY sai.subaward_code, sai.sequence_number, sai.subaward_amount_info_id
FETCH FIRST 10 ROWS ONLY;

SELECT
    MIN(sai.sequence_number) AS min_sequence_number,
    MAX(sai.sequence_number) AS max_sequence_number,
    SUM(CASE WHEN sai.sequence_number IS NULL THEN 1 ELSE 0 END) AS null_sequence_numbers,
    SUM(CASE WHEN sai.subaward_code IS NULL THEN 1 ELSE 0 END) AS null_subaward_codes
FROM KCOEUS.SUBAWARD_AMOUNT_INFO sai;

SELECT COUNT(*) AS inconsistent_parent_business_keys
FROM KCOEUS.SUBAWARD_AMOUNT_INFO sai
JOIN KCOEUS.SUBAWARD s ON s.subaward_id = sai.subaward_id
WHERE sai.subaward_code <> s.subaward_code
   OR sai.sequence_number <> s.sequence_number;

-- ============================================================================
-- Contacts
-- ============================================================================
SELECT COUNT(*) AS total_rows FROM KCOEUS.SUBAWARD_CONTACT;
SELECT COUNT(DISTINCT sc.subaward_contact_id) AS distinct_primary_keys FROM KCOEUS.SUBAWARD_CONTACT sc;
SELECT sc.subaward_contact_id, COUNT(*) AS duplicate_count FROM KCOEUS.SUBAWARD_CONTACT sc GROUP BY sc.subaward_contact_id HAVING COUNT(*) > 1 ORDER BY sc.subaward_contact_id;
SELECT COUNT(*) AS null_primary_keys FROM KCOEUS.SUBAWARD_CONTACT sc WHERE sc.subaward_contact_id IS NULL;
SELECT COUNT(*) AS orphan_parent_keys FROM KCOEUS.SUBAWARD_CONTACT sc LEFT JOIN KCOEUS.SUBAWARD s ON s.subaward_id = sc.subaward_id WHERE sc.subaward_id IS NOT NULL AND s.subaward_id IS NULL;
SELECT MIN(sc.update_timestamp) AS min_update_timestamp, MAX(sc.update_timestamp) AS max_update_timestamp FROM KCOEUS.SUBAWARD_CONTACT sc;
SELECT SUM(CASE WHEN sc.update_timestamp IS NULL THEN 1 ELSE 0 END) AS null_update_timestamp, SUM(CASE WHEN sc.update_user IS NULL THEN 1 ELSE 0 END) AS null_update_user, SUM(CASE WHEN sc.ver_nbr IS NULL THEN 1 ELSE 0 END) AS null_ver_nbr, SUM(CASE WHEN sc.obj_id IS NULL THEN 1 ELSE 0 END) AS null_obj_id FROM KCOEUS.SUBAWARD_CONTACT sc;
SELECT ROUND(100 * SUM(CASE WHEN sc.subaward_id IS NULL THEN 1 ELSE 0 END) / NULLIF(COUNT(*), 0), 2) AS subaward_id_null_pct, ROUND(100 * SUM(CASE WHEN sc.contact_type_code IS NULL THEN 1 ELSE 0 END) / NULLIF(COUNT(*), 0), 2) AS contact_type_code_null_pct, ROUND(100 * SUM(CASE WHEN sc.rolodex_id IS NULL THEN 1 ELSE 0 END) / NULLIF(COUNT(*), 0), 2) AS rolodex_id_null_pct FROM KCOEUS.SUBAWARD_CONTACT sc;
SELECT sc.* FROM KCOEUS.SUBAWARD_CONTACT sc ORDER BY sc.subaward_code, sc.sequence_number, sc.subaward_contact_id FETCH FIRST 10 ROWS ONLY;
SELECT MIN(sc.sequence_number) AS min_sequence_number, MAX(sc.sequence_number) AS max_sequence_number, SUM(CASE WHEN sc.sequence_number IS NULL THEN 1 ELSE 0 END) AS null_sequence_numbers FROM KCOEUS.SUBAWARD_CONTACT sc;
SELECT COUNT(*) AS inconsistent_parent_business_keys FROM KCOEUS.SUBAWARD_CONTACT sc JOIN KCOEUS.SUBAWARD s ON s.subaward_id = sc.subaward_id WHERE sc.subaward_code <> s.subaward_code OR sc.sequence_number <> s.sequence_number;

-- ============================================================================
-- Custom data
-- ============================================================================
SELECT COUNT(*) AS total_rows FROM KCOEUS.SUBAWARD_CUSTOM_DATA;
SELECT COUNT(DISTINCT scd.subaward_custom_data_id) AS distinct_primary_keys FROM KCOEUS.SUBAWARD_CUSTOM_DATA scd;
SELECT scd.subaward_custom_data_id, COUNT(*) AS duplicate_count FROM KCOEUS.SUBAWARD_CUSTOM_DATA scd GROUP BY scd.subaward_custom_data_id HAVING COUNT(*) > 1 ORDER BY scd.subaward_custom_data_id;
SELECT COUNT(*) AS null_primary_keys FROM KCOEUS.SUBAWARD_CUSTOM_DATA scd WHERE scd.subaward_custom_data_id IS NULL;
SELECT COUNT(*) AS orphan_parent_keys FROM KCOEUS.SUBAWARD_CUSTOM_DATA scd LEFT JOIN KCOEUS.SUBAWARD s ON s.subaward_id = scd.subaward_id WHERE scd.subaward_id IS NOT NULL AND s.subaward_id IS NULL;
SELECT MIN(scd.update_timestamp) AS min_update_timestamp, MAX(scd.update_timestamp) AS max_update_timestamp FROM KCOEUS.SUBAWARD_CUSTOM_DATA scd;
SELECT SUM(CASE WHEN scd.update_timestamp IS NULL THEN 1 ELSE 0 END) AS null_update_timestamp, SUM(CASE WHEN scd.update_user IS NULL THEN 1 ELSE 0 END) AS null_update_user, SUM(CASE WHEN scd.ver_nbr IS NULL THEN 1 ELSE 0 END) AS null_ver_nbr, SUM(CASE WHEN scd.obj_id IS NULL THEN 1 ELSE 0 END) AS null_obj_id FROM KCOEUS.SUBAWARD_CUSTOM_DATA scd;
SELECT ROUND(100 * SUM(CASE WHEN scd.subaward_id IS NULL THEN 1 ELSE 0 END) / NULLIF(COUNT(*), 0), 2) AS subaward_id_null_pct, ROUND(100 * SUM(CASE WHEN scd.custom_attribute_id IS NULL THEN 1 ELSE 0 END) / NULLIF(COUNT(*), 0), 2) AS custom_attribute_id_null_pct, ROUND(100 * SUM(CASE WHEN scd.value IS NULL THEN 1 ELSE 0 END) / NULLIF(COUNT(*), 0), 2) AS value_null_pct FROM KCOEUS.SUBAWARD_CUSTOM_DATA scd;
SELECT scd.* FROM KCOEUS.SUBAWARD_CUSTOM_DATA scd ORDER BY scd.subaward_code, scd.sequence_number, scd.subaward_custom_data_id FETCH FIRST 10 ROWS ONLY;
SELECT MIN(scd.sequence_number) AS min_sequence_number, MAX(scd.sequence_number) AS max_sequence_number, SUM(CASE WHEN scd.sequence_number IS NULL THEN 1 ELSE 0 END) AS null_sequence_numbers FROM KCOEUS.SUBAWARD_CUSTOM_DATA scd;
SELECT COUNT(*) AS inconsistent_parent_business_keys FROM KCOEUS.SUBAWARD_CUSTOM_DATA scd JOIN KCOEUS.SUBAWARD s ON s.subaward_id = scd.subaward_id WHERE scd.subaward_code <> s.subaward_code OR scd.sequence_number <> s.sequence_number;

-- ============================================================================
-- Funding
-- ============================================================================
SELECT COUNT(*) AS total_rows FROM KCOEUS.SUBAWARD_FUNDING_SOURCE;
SELECT COUNT(DISTINCT sfs.subaward_funding_source_id) AS distinct_primary_keys FROM KCOEUS.SUBAWARD_FUNDING_SOURCE sfs;
SELECT sfs.subaward_funding_source_id, COUNT(*) AS duplicate_count FROM KCOEUS.SUBAWARD_FUNDING_SOURCE sfs GROUP BY sfs.subaward_funding_source_id HAVING COUNT(*) > 1 ORDER BY sfs.subaward_funding_source_id;
SELECT COUNT(*) AS null_primary_keys FROM KCOEUS.SUBAWARD_FUNDING_SOURCE sfs WHERE sfs.subaward_funding_source_id IS NULL;
SELECT COUNT(*) AS orphan_parent_keys FROM KCOEUS.SUBAWARD_FUNDING_SOURCE sfs LEFT JOIN KCOEUS.SUBAWARD s ON s.subaward_id = sfs.subaward_id WHERE sfs.subaward_id IS NOT NULL AND s.subaward_id IS NULL;
SELECT MIN(sfs.update_timestamp) AS min_update_timestamp, MAX(sfs.update_timestamp) AS max_update_timestamp FROM KCOEUS.SUBAWARD_FUNDING_SOURCE sfs;
SELECT SUM(CASE WHEN sfs.update_timestamp IS NULL THEN 1 ELSE 0 END) AS null_update_timestamp, SUM(CASE WHEN sfs.update_user IS NULL THEN 1 ELSE 0 END) AS null_update_user, SUM(CASE WHEN sfs.ver_nbr IS NULL THEN 1 ELSE 0 END) AS null_ver_nbr, SUM(CASE WHEN sfs.obj_id IS NULL THEN 1 ELSE 0 END) AS null_obj_id FROM KCOEUS.SUBAWARD_FUNDING_SOURCE sfs;
SELECT ROUND(100 * SUM(CASE WHEN sfs.subaward_id IS NULL THEN 1 ELSE 0 END) / NULLIF(COUNT(*), 0), 2) AS subaward_id_null_pct, ROUND(100 * SUM(CASE WHEN sfs.award_id IS NULL THEN 1 ELSE 0 END) / NULLIF(COUNT(*), 0), 2) AS award_id_null_pct FROM KCOEUS.SUBAWARD_FUNDING_SOURCE sfs;
SELECT sfs.* FROM KCOEUS.SUBAWARD_FUNDING_SOURCE sfs ORDER BY sfs.subaward_code, sfs.sequence_number, sfs.subaward_funding_source_id FETCH FIRST 10 ROWS ONLY;
SELECT MIN(sfs.sequence_number) AS min_sequence_number, MAX(sfs.sequence_number) AS max_sequence_number, SUM(CASE WHEN sfs.sequence_number IS NULL THEN 1 ELSE 0 END) AS null_sequence_numbers FROM KCOEUS.SUBAWARD_FUNDING_SOURCE sfs;
SELECT COUNT(*) AS inconsistent_parent_business_keys FROM KCOEUS.SUBAWARD_FUNDING_SOURCE sfs JOIN KCOEUS.SUBAWARD s ON s.subaward_id = sfs.subaward_id WHERE sfs.subaward_code <> s.subaward_code OR sfs.sequence_number <> s.sequence_number;

-- ============================================================================
-- Attachments
-- ============================================================================
SELECT COUNT(*) AS total_rows FROM KCOEUS.SUBAWARD_ATTACHMENTS;
SELECT COUNT(DISTINCT sa.attachment_id) AS distinct_primary_keys FROM KCOEUS.SUBAWARD_ATTACHMENTS sa;
SELECT sa.attachment_id, COUNT(*) AS duplicate_count FROM KCOEUS.SUBAWARD_ATTACHMENTS sa GROUP BY sa.attachment_id HAVING COUNT(*) > 1 ORDER BY sa.attachment_id;
SELECT COUNT(*) AS null_primary_keys FROM KCOEUS.SUBAWARD_ATTACHMENTS sa WHERE sa.attachment_id IS NULL;
SELECT COUNT(*) AS orphan_parent_keys FROM KCOEUS.SUBAWARD_ATTACHMENTS sa LEFT JOIN KCOEUS.SUBAWARD s ON s.subaward_id = sa.subaward_id WHERE sa.subaward_id IS NOT NULL AND s.subaward_id IS NULL;
SELECT COUNT(*) AS missing_attachment_type_rows FROM KCOEUS.SUBAWARD_ATTACHMENTS sa LEFT JOIN KCOEUS.SUBAWARD_ATTACHMENT_TYPE sat ON sat.attachment_type_code = sa.attachment_type_code WHERE sa.attachment_type_code IS NOT NULL AND sat.attachment_type_code IS NULL;
SELECT MIN(sa.update_timestamp) AS min_update_timestamp, MAX(sa.update_timestamp) AS max_update_timestamp, MIN(sa.last_update_timestamp) AS min_last_update_timestamp, MAX(sa.last_update_timestamp) AS max_last_update_timestamp FROM KCOEUS.SUBAWARD_ATTACHMENTS sa;
SELECT SUM(CASE WHEN sa.update_timestamp IS NULL THEN 1 ELSE 0 END) AS null_update_timestamp, SUM(CASE WHEN sa.update_user IS NULL THEN 1 ELSE 0 END) AS null_update_user, SUM(CASE WHEN sa.ver_nbr IS NULL THEN 1 ELSE 0 END) AS null_ver_nbr, SUM(CASE WHEN sa.obj_id IS NULL THEN 1 ELSE 0 END) AS null_obj_id FROM KCOEUS.SUBAWARD_ATTACHMENTS sa;
SELECT ROUND(100 * SUM(CASE WHEN sa.subaward_id IS NULL THEN 1 ELSE 0 END) / NULLIF(COUNT(*), 0), 2) AS subaward_id_null_pct, ROUND(100 * SUM(CASE WHEN sa.attachment_type_code IS NULL THEN 1 ELSE 0 END) / NULLIF(COUNT(*), 0), 2) AS attachment_type_code_null_pct, ROUND(100 * SUM(CASE WHEN sa.file_data_id IS NULL THEN 1 ELSE 0 END) / NULLIF(COUNT(*), 0), 2) AS file_data_id_null_pct, ROUND(100 * SUM(CASE WHEN sa.file_name IS NULL THEN 1 ELSE 0 END) / NULLIF(COUNT(*), 0), 2) AS file_name_null_pct, ROUND(100 * SUM(CASE WHEN sa.mime_type IS NULL THEN 1 ELSE 0 END) / NULLIF(COUNT(*), 0), 2) AS mime_type_null_pct FROM KCOEUS.SUBAWARD_ATTACHMENTS sa;
SELECT sa.* FROM KCOEUS.SUBAWARD_ATTACHMENTS sa ORDER BY sa.subaward_code, sa.sequence_number, sa.attachment_id FETCH FIRST 10 ROWS ONLY;
SELECT MIN(sa.sequence_number) AS min_sequence_number, MAX(sa.sequence_number) AS max_sequence_number, SUM(CASE WHEN sa.sequence_number IS NULL THEN 1 ELSE 0 END) AS null_sequence_numbers FROM KCOEUS.SUBAWARD_ATTACHMENTS sa;
SELECT COUNT(*) AS inconsistent_parent_business_keys FROM KCOEUS.SUBAWARD_ATTACHMENTS sa JOIN KCOEUS.SUBAWARD s ON s.subaward_id = sa.subaward_id WHERE sa.subaward_code <> s.subaward_code OR sa.sequence_number <> s.sequence_number;
SELECT SUM(CASE WHEN sa.file_data_id IS NOT NULL THEN 1 ELSE 0 END) AS rows_with_file_data_id, SUM(CASE WHEN sa.document_id IS NOT NULL THEN 1 ELSE 0 END) AS rows_with_document_id, SUM(CASE WHEN sa.file_name IS NOT NULL THEN 1 ELSE 0 END) AS rows_with_file_name, SUM(CASE WHEN sa.mime_type IS NOT NULL THEN 1 ELSE 0 END) AS rows_with_mime_type, SUM(CASE WHEN sa.file_data_id IS NOT NULL AND sa.file_name IS NULL THEN 1 ELSE 0 END) AS file_id_without_name FROM KCOEUS.SUBAWARD_ATTACHMENTS sa;

-- ============================================================================
-- Closeout
-- ============================================================================
SELECT COUNT(*) AS total_rows FROM KCOEUS.SUBAWARD_CLOSEOUT;
SELECT COUNT(DISTINCT sc.subaward_closeout_id) AS distinct_primary_keys FROM KCOEUS.SUBAWARD_CLOSEOUT sc;
SELECT sc.subaward_closeout_id, COUNT(*) AS duplicate_count FROM KCOEUS.SUBAWARD_CLOSEOUT sc GROUP BY sc.subaward_closeout_id HAVING COUNT(*) > 1 ORDER BY sc.subaward_closeout_id;
SELECT COUNT(*) AS null_primary_keys FROM KCOEUS.SUBAWARD_CLOSEOUT sc WHERE sc.subaward_closeout_id IS NULL;
SELECT COUNT(*) AS orphan_parent_keys FROM KCOEUS.SUBAWARD_CLOSEOUT sc LEFT JOIN KCOEUS.SUBAWARD s ON s.subaward_id = sc.subaward_id WHERE sc.subaward_id IS NOT NULL AND s.subaward_id IS NULL;
SELECT MIN(sc.date_requested) AS min_date_requested, MAX(sc.date_requested) AS max_date_requested, MIN(sc.date_followup) AS min_date_followup, MAX(sc.date_followup) AS max_date_followup, MIN(sc.date_received) AS min_date_received, MAX(sc.date_received) AS max_date_received, MIN(sc.update_timestamp) AS min_update_timestamp, MAX(sc.update_timestamp) AS max_update_timestamp FROM KCOEUS.SUBAWARD_CLOSEOUT sc;
SELECT SUM(CASE WHEN sc.update_timestamp IS NULL THEN 1 ELSE 0 END) AS null_update_timestamp, SUM(CASE WHEN sc.update_user IS NULL THEN 1 ELSE 0 END) AS null_update_user, SUM(CASE WHEN sc.ver_nbr IS NULL THEN 1 ELSE 0 END) AS null_ver_nbr, SUM(CASE WHEN sc.obj_id IS NULL THEN 1 ELSE 0 END) AS null_obj_id FROM KCOEUS.SUBAWARD_CLOSEOUT sc;
SELECT ROUND(100 * SUM(CASE WHEN sc.subaward_id IS NULL THEN 1 ELSE 0 END) / NULLIF(COUNT(*), 0), 2) AS subaward_id_null_pct, ROUND(100 * SUM(CASE WHEN sc.closeout_type_code IS NULL THEN 1 ELSE 0 END) / NULLIF(COUNT(*), 0), 2) AS closeout_type_code_null_pct, ROUND(100 * SUM(CASE WHEN sc.date_received IS NULL THEN 1 ELSE 0 END) / NULLIF(COUNT(*), 0), 2) AS date_received_null_pct FROM KCOEUS.SUBAWARD_CLOSEOUT sc;
SELECT sc.* FROM KCOEUS.SUBAWARD_CLOSEOUT sc ORDER BY sc.subaward_code, sc.sequence_number, sc.subaward_closeout_id FETCH FIRST 10 ROWS ONLY;
SELECT MIN(sc.sequence_number) AS min_sequence_number, MAX(sc.sequence_number) AS max_sequence_number, SUM(CASE WHEN sc.sequence_number IS NULL THEN 1 ELSE 0 END) AS null_sequence_numbers FROM KCOEUS.SUBAWARD_CLOSEOUT sc;
SELECT COUNT(*) AS inconsistent_parent_business_keys FROM KCOEUS.SUBAWARD_CLOSEOUT sc JOIN KCOEUS.SUBAWARD s ON s.subaward_id = sc.subaward_id WHERE sc.subaward_code <> s.subaward_code OR sc.sequence_number <> s.sequence_number;

-- ============================================================================
-- Reports
-- ============================================================================
SELECT COUNT(*) AS total_rows FROM KCOEUS.SUBAWARD_REPORTS;
SELECT COUNT(DISTINCT sr.subaward_report_id) AS distinct_primary_keys FROM KCOEUS.SUBAWARD_REPORTS sr;
SELECT sr.subaward_report_id, COUNT(*) AS duplicate_count FROM KCOEUS.SUBAWARD_REPORTS sr GROUP BY sr.subaward_report_id HAVING COUNT(*) > 1 ORDER BY sr.subaward_report_id;
SELECT COUNT(*) AS null_primary_keys FROM KCOEUS.SUBAWARD_REPORTS sr WHERE sr.subaward_report_id IS NULL;
SELECT COUNT(*) AS orphan_parent_keys FROM KCOEUS.SUBAWARD_REPORTS sr LEFT JOIN KCOEUS.SUBAWARD s ON s.subaward_id = sr.subaward_id WHERE sr.subaward_id IS NOT NULL AND s.subaward_id IS NULL;
SELECT COUNT(*) AS missing_report_type_rows FROM KCOEUS.SUBAWARD_REPORTS sr LEFT JOIN KCOEUS.SUBAWARD_REPORT_TYPE srt ON srt.report_type_code = sr.report_type_code WHERE sr.report_type_code IS NOT NULL AND srt.report_type_code IS NULL;
SELECT MIN(sr.update_timestamp) AS min_update_timestamp, MAX(sr.update_timestamp) AS max_update_timestamp FROM KCOEUS.SUBAWARD_REPORTS sr;
SELECT SUM(CASE WHEN sr.update_timestamp IS NULL THEN 1 ELSE 0 END) AS null_update_timestamp, SUM(CASE WHEN sr.update_user IS NULL THEN 1 ELSE 0 END) AS null_update_user, SUM(CASE WHEN sr.ver_nbr IS NULL THEN 1 ELSE 0 END) AS null_ver_nbr, SUM(CASE WHEN sr.obj_id IS NULL THEN 1 ELSE 0 END) AS null_obj_id FROM KCOEUS.SUBAWARD_REPORTS sr;
SELECT ROUND(100 * SUM(CASE WHEN sr.subaward_id IS NULL THEN 1 ELSE 0 END) / NULLIF(COUNT(*), 0), 2) AS subaward_id_null_pct, ROUND(100 * SUM(CASE WHEN sr.report_type_code IS NULL THEN 1 ELSE 0 END) / NULLIF(COUNT(*), 0), 2) AS report_type_code_null_pct FROM KCOEUS.SUBAWARD_REPORTS sr;
SELECT sr.* FROM KCOEUS.SUBAWARD_REPORTS sr ORDER BY sr.subaward_code, sr.sequence_number, sr.subaward_report_id FETCH FIRST 10 ROWS ONLY;
SELECT MIN(sr.sequence_number) AS min_sequence_number, MAX(sr.sequence_number) AS max_sequence_number, SUM(CASE WHEN sr.sequence_number IS NULL THEN 1 ELSE 0 END) AS null_sequence_numbers FROM KCOEUS.SUBAWARD_REPORTS sr;
SELECT COUNT(*) AS inconsistent_parent_business_keys FROM KCOEUS.SUBAWARD_REPORTS sr JOIN KCOEUS.SUBAWARD s ON s.subaward_id = sr.subaward_id WHERE sr.subaward_code <> s.subaward_code OR sr.sequence_number <> s.sequence_number;

-- ============================================================================
-- Notepad
-- ============================================================================
SELECT COUNT(*) AS total_rows FROM KCOEUS.SUBAWARD_NOTEPAD;
SELECT COUNT(DISTINCT sn.subaward_notepad_id) AS distinct_primary_keys FROM KCOEUS.SUBAWARD_NOTEPAD sn;
SELECT sn.subaward_notepad_id, COUNT(*) AS duplicate_count FROM KCOEUS.SUBAWARD_NOTEPAD sn GROUP BY sn.subaward_notepad_id HAVING COUNT(*) > 1 ORDER BY sn.subaward_notepad_id;
SELECT COUNT(*) AS null_primary_keys FROM KCOEUS.SUBAWARD_NOTEPAD sn WHERE sn.subaward_notepad_id IS NULL;
SELECT COUNT(*) AS orphan_parent_keys FROM KCOEUS.SUBAWARD_NOTEPAD sn LEFT JOIN KCOEUS.SUBAWARD s ON s.subaward_id = sn.subaward_id WHERE sn.subaward_id IS NOT NULL AND s.subaward_id IS NULL;
SELECT MIN(sn.create_timestamp) AS min_create_timestamp, MAX(sn.create_timestamp) AS max_create_timestamp, MIN(sn.update_timestamp) AS min_update_timestamp, MAX(sn.update_timestamp) AS max_update_timestamp FROM KCOEUS.SUBAWARD_NOTEPAD sn;
SELECT SUM(CASE WHEN sn.create_timestamp IS NULL THEN 1 ELSE 0 END) AS null_create_timestamp, SUM(CASE WHEN sn.create_user IS NULL THEN 1 ELSE 0 END) AS null_create_user, SUM(CASE WHEN sn.update_timestamp IS NULL THEN 1 ELSE 0 END) AS null_update_timestamp, SUM(CASE WHEN sn.update_user IS NULL THEN 1 ELSE 0 END) AS null_update_user, SUM(CASE WHEN sn.ver_nbr IS NULL THEN 1 ELSE 0 END) AS null_ver_nbr, SUM(CASE WHEN sn.obj_id IS NULL THEN 1 ELSE 0 END) AS null_obj_id FROM KCOEUS.SUBAWARD_NOTEPAD sn;
SELECT ROUND(100 * SUM(CASE WHEN sn.subaward_id IS NULL THEN 1 ELSE 0 END) / NULLIF(COUNT(*), 0), 2) AS subaward_id_null_pct, ROUND(100 * SUM(CASE WHEN sn.entry_number IS NULL THEN 1 ELSE 0 END) / NULLIF(COUNT(*), 0), 2) AS entry_number_null_pct, ROUND(100 * SUM(CASE WHEN sn.note_topic IS NULL THEN 1 ELSE 0 END) / NULLIF(COUNT(*), 0), 2) AS note_topic_null_pct, ROUND(100 * SUM(CASE WHEN sn.comments IS NULL THEN 1 ELSE 0 END) / NULLIF(COUNT(*), 0), 2) AS comments_null_pct, ROUND(100 * SUM(CASE WHEN sn.restricted_view IS NULL THEN 1 ELSE 0 END) / NULLIF(COUNT(*), 0), 2) AS restricted_view_null_pct FROM KCOEUS.SUBAWARD_NOTEPAD sn;
SELECT sn.* FROM KCOEUS.SUBAWARD_NOTEPAD sn ORDER BY sn.subaward_code, sn.entry_number, sn.subaward_notepad_id FETCH FIRST 10 ROWS ONLY;
SELECT COUNT(*) AS inconsistent_parent_subaward_codes FROM KCOEUS.SUBAWARD_NOTEPAD sn JOIN KCOEUS.SUBAWARD s ON s.subaward_id = sn.subaward_id WHERE sn.subaward_code <> s.subaward_code;

-- ============================================================================
-- Notifications
-- ============================================================================
SELECT COUNT(*) AS notification_row_count FROM KCOEUS.SUBAWARD_NOTIFICATION;
SELECT COUNT(DISTINCT sn.notification_id) AS distinct_primary_keys FROM KCOEUS.SUBAWARD_NOTIFICATION sn;
SELECT sn.notification_id, COUNT(*) AS duplicate_count FROM KCOEUS.SUBAWARD_NOTIFICATION sn GROUP BY sn.notification_id HAVING COUNT(*) > 1 ORDER BY sn.notification_id;
SELECT COUNT(*) AS null_primary_keys FROM KCOEUS.SUBAWARD_NOTIFICATION sn WHERE sn.notification_id IS NULL;
SELECT COUNT(*) AS orphan_owning_document_keys FROM KCOEUS.SUBAWARD_NOTIFICATION sn LEFT JOIN KCOEUS.SUBAWARD s ON s.subaward_id = sn.owning_document_id_fk WHERE sn.owning_document_id_fk IS NOT NULL AND s.subaward_id IS NULL;
SELECT COUNT(*) AS orphan_document_numbers FROM KCOEUS.SUBAWARD_NOTIFICATION sn LEFT JOIN KCOEUS.SUBAWARD_DOCUMENT sd ON sd.document_number = sn.document_number WHERE sn.document_number IS NOT NULL AND sd.document_number IS NULL;
SELECT MIN(sn.create_timestamp) AS min_create_timestamp, MAX(sn.create_timestamp) AS max_create_timestamp, MIN(sn.update_timestamp) AS min_update_timestamp, MAX(sn.update_timestamp) AS max_update_timestamp FROM KCOEUS.SUBAWARD_NOTIFICATION sn;
SELECT SUM(CASE WHEN sn.update_timestamp IS NULL THEN 1 ELSE 0 END) AS null_update_timestamp, SUM(CASE WHEN sn.update_user IS NULL THEN 1 ELSE 0 END) AS null_update_user, SUM(CASE WHEN sn.ver_nbr IS NULL THEN 1 ELSE 0 END) AS null_ver_nbr, SUM(CASE WHEN sn.obj_id IS NULL THEN 1 ELSE 0 END) AS null_obj_id FROM KCOEUS.SUBAWARD_NOTIFICATION sn;
SELECT ROUND(100 * SUM(CASE WHEN sn.owning_document_id_fk IS NULL THEN 1 ELSE 0 END) / NULLIF(COUNT(*), 0), 2) AS owning_document_id_fk_null_pct, ROUND(100 * SUM(CASE WHEN sn.document_number IS NULL THEN 1 ELSE 0 END) / NULLIF(COUNT(*), 0), 2) AS document_number_null_pct, ROUND(100 * SUM(CASE WHEN sn.notification_type_id IS NULL THEN 1 ELSE 0 END) / NULLIF(COUNT(*), 0), 2) AS notification_type_id_null_pct, ROUND(100 * SUM(CASE WHEN sn.recipients IS NULL THEN 1 ELSE 0 END) / NULLIF(COUNT(*), 0), 2) AS recipients_null_pct, ROUND(100 * SUM(CASE WHEN sn.subject IS NULL THEN 1 ELSE 0 END) / NULLIF(COUNT(*), 0), 2) AS subject_null_pct, ROUND(100 * SUM(CASE WHEN sn.message IS NULL THEN 1 ELSE 0 END) / NULLIF(COUNT(*), 0), 2) AS message_null_pct FROM KCOEUS.SUBAWARD_NOTIFICATION sn;
SELECT sn.* FROM KCOEUS.SUBAWARD_NOTIFICATION sn ORDER BY sn.subaward_code, sn.create_timestamp, sn.notification_id FETCH FIRST 10 ROWS ONLY;
SELECT COUNT(*) AS inconsistent_parent_values FROM KCOEUS.SUBAWARD_NOTIFICATION sn JOIN KCOEUS.SUBAWARD s ON s.subaward_id = sn.owning_document_id_fk WHERE (sn.subaward_code <> s.subaward_code) OR (sn.document_number <> s.document_number);

-- ============================================================================
-- Template info
-- ============================================================================
SELECT COUNT(*) AS total_rows FROM KCOEUS.SUBAWARD_TEMPLATE_INFO;
SELECT COUNT(DISTINCT sti.subaward_id) AS distinct_primary_keys FROM KCOEUS.SUBAWARD_TEMPLATE_INFO sti;
SELECT sti.subaward_id, COUNT(*) AS duplicate_count FROM KCOEUS.SUBAWARD_TEMPLATE_INFO sti GROUP BY sti.subaward_id HAVING COUNT(*) > 1 ORDER BY sti.subaward_id;
SELECT COUNT(*) AS null_primary_keys FROM KCOEUS.SUBAWARD_TEMPLATE_INFO sti WHERE sti.subaward_id IS NULL;
SELECT COUNT(*) AS orphan_parent_keys FROM KCOEUS.SUBAWARD_TEMPLATE_INFO sti LEFT JOIN KCOEUS.SUBAWARD s ON s.subaward_id = sti.subaward_id WHERE sti.subaward_id IS NOT NULL AND s.subaward_id IS NULL;
SELECT MIN(sti.sub_proposal_date) AS min_sub_proposal_date, MAX(sti.sub_proposal_date) AS max_sub_proposal_date, MIN(sti.applicable_program_regs_date) AS min_program_regs_date, MAX(sti.applicable_program_regs_date) AS max_program_regs_date, MIN(sti.update_timestamp) AS min_update_timestamp, MAX(sti.update_timestamp) AS max_update_timestamp FROM KCOEUS.SUBAWARD_TEMPLATE_INFO sti;
SELECT SUM(CASE WHEN sti.update_timestamp IS NULL THEN 1 ELSE 0 END) AS null_update_timestamp, SUM(CASE WHEN sti.update_user IS NULL THEN 1 ELSE 0 END) AS null_update_user FROM KCOEUS.SUBAWARD_TEMPLATE_INFO sti;
SELECT ROUND(100 * SUM(CASE WHEN sti.subaward_code IS NULL THEN 1 ELSE 0 END) / NULLIF(COUNT(*), 0), 2) AS subaward_code_null_pct, ROUND(100 * SUM(CASE WHEN sti.sequence_number IS NULL THEN 1 ELSE 0 END) / NULLIF(COUNT(*), 0), 2) AS sequence_number_null_pct, ROUND(100 * SUM(CASE WHEN sti.sow_or_sub_proposal_budget IS NULL THEN 1 ELSE 0 END) / NULLIF(COUNT(*), 0), 2) AS sow_or_budget_null_pct, ROUND(100 * SUM(CASE WHEN sti.copyright_type IS NULL THEN 1 ELSE 0 END) / NULLIF(COUNT(*), 0), 2) AS copyright_type_null_pct, ROUND(100 * SUM(CASE WHEN sti.data_sharing_attachment IS NULL THEN 1 ELSE 0 END) / NULLIF(COUNT(*), 0), 2) AS data_sharing_attachment_null_pct FROM KCOEUS.SUBAWARD_TEMPLATE_INFO sti;
SELECT sti.* FROM KCOEUS.SUBAWARD_TEMPLATE_INFO sti ORDER BY sti.subaward_code, sti.sequence_number, sti.subaward_id FETCH FIRST 10 ROWS ONLY;
SELECT MIN(sti.sequence_number) AS min_sequence_number, MAX(sti.sequence_number) AS max_sequence_number, SUM(CASE WHEN sti.sequence_number IS NULL THEN 1 ELSE 0 END) AS null_sequence_numbers FROM KCOEUS.SUBAWARD_TEMPLATE_INFO sti;
SELECT COUNT(*) AS inconsistent_parent_business_keys FROM KCOEUS.SUBAWARD_TEMPLATE_INFO sti JOIN KCOEUS.SUBAWARD s ON s.subaward_id = sti.subaward_id WHERE sti.subaward_code <> s.subaward_code OR sti.sequence_number <> s.sequence_number;
SELECT COUNT(*) AS parents_with_multiple_template_rows FROM (SELECT sti.subaward_id FROM KCOEUS.SUBAWARD_TEMPLATE_INFO sti GROUP BY sti.subaward_id HAVING COUNT(*) > 1);
