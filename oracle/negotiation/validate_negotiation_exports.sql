-- Negotiation Oracle validation package
-- Run locally while connected to BU Oracle. All statements are read-only.
-- Each section corresponds to one CSV export and returns independent result
-- sets so failures and data-quality findings can be recorded separately.

-- ============================================================================
-- negotiations
-- ============================================================================

-- 1-4. Total rows, distinct primary keys, duplicate-key surplus, NULL keys.
SELECT
    COUNT(*) AS total_row_count,
    COUNT(DISTINCT n.negotiation_id) AS distinct_primary_key_count,
    COUNT(*) - COUNT(DISTINCT n.negotiation_id) AS duplicate_primary_key_rows,
    SUM(CASE WHEN n.negotiation_id IS NULL THEN 1 ELSE 0 END) AS null_primary_keys
FROM KCOEUS.NEGOTIATION n;

-- 3. Duplicate primary-key values (expect no rows).
SELECT n.negotiation_id, COUNT(*) AS duplicate_count
FROM KCOEUS.NEGOTIATION n
GROUP BY n.negotiation_id
HAVING COUNT(*) > 1
ORDER BY n.negotiation_id;

-- Additional business-key check: DOCUMENT_NUMBER uniqueness.
SELECT n.document_number, COUNT(*) AS negotiation_count
FROM KCOEUS.NEGOTIATION n
GROUP BY n.document_number
HAVING COUNT(*) > 1
ORDER BY n.document_number;

-- 5. Orphan DOCUMENT_NUMBER values (expect zero).
SELECT COUNT(*) AS orphan_document_number_count
FROM KCOEUS.NEGOTIATION n
LEFT JOIN KCOEUS.NEGOTIATION_DOCUMENT nd
    ON nd.document_number = n.document_number
WHERE n.document_number IS NOT NULL
  AND nd.document_number IS NULL;

-- 6. Missing lookup rows and lookup descriptions.
SELECT
    SUM(CASE WHEN n.negotation_status_id IS NOT NULL
                  AND ns.negotiation_status_id IS NULL THEN 1 ELSE 0 END)
        AS missing_status_lookup_count,
    SUM(CASE WHEN ns.negotiation_status_id IS NOT NULL
                  AND ns.description IS NULL THEN 1 ELSE 0 END)
        AS null_status_description_count,
    SUM(CASE WHEN n.negotiation_agreement_type_id IS NOT NULL
                  AND nat.negotiation_agrmnt_type_id IS NULL THEN 1 ELSE 0 END)
        AS missing_agreement_type_lookup_count,
    SUM(CASE WHEN nat.negotiation_agrmnt_type_id IS NOT NULL
                  AND nat.description IS NULL THEN 1 ELSE 0 END)
        AS null_agreement_type_description_count,
    SUM(CASE WHEN n.negotiation_assc_type_id IS NOT NULL
                  AND nast.negotiation_assc_type_id IS NULL THEN 1 ELSE 0 END)
        AS missing_association_type_lookup_count,
    SUM(CASE WHEN nast.negotiation_assc_type_id IS NOT NULL
                  AND nast.description IS NULL THEN 1 ELSE 0 END)
        AS null_association_type_description_count
FROM KCOEUS.NEGOTIATION n
LEFT JOIN KCOEUS.NEGOTIATION_STATUS ns
    ON ns.negotiation_status_id = n.negotation_status_id
LEFT JOIN KCOEUS.NEGOTIATION_AGREEMENT_TYPE nat
    ON nat.negotiation_agrmnt_type_id = n.negotiation_agreement_type_id
LEFT JOIN KCOEUS.NEGOTIATION_ASSOCIATION_TYPE nast
    ON nast.negotiation_assc_type_id = n.negotiation_assc_type_id;

-- 7. Sample 10 export rows.
SELECT *
FROM (
    SELECT
        n.negotiation_id,
        n.document_number,
        n.negotation_status_id AS negotiation_status_id,
        ns.description AS negotiation_status_description,
        n.negotiation_agreement_type_id,
        nat.description AS negotiation_agreement_type_description,
        n.negotiation_assc_type_id AS negotiation_association_type_id,
        nast.description AS negotiation_association_type_description,
        n.associated_document_id,
        n.negotiation_start_date,
        n.negotiation_end_date,
        n.update_timestamp,
        n.update_user,
        n.ver_nbr,
        n.obj_id
    FROM KCOEUS.NEGOTIATION n
    LEFT JOIN KCOEUS.NEGOTIATION_STATUS ns
        ON ns.negotiation_status_id = n.negotation_status_id
    LEFT JOIN KCOEUS.NEGOTIATION_AGREEMENT_TYPE nat
        ON nat.negotiation_agrmnt_type_id = n.negotiation_agreement_type_id
    LEFT JOIN KCOEUS.NEGOTIATION_ASSOCIATION_TYPE nast
        ON nast.negotiation_assc_type_id = n.negotiation_assc_type_id
    ORDER BY n.negotiation_id
)
WHERE ROWNUM <= 10;

-- 8. Date and audit timestamp ranges.
SELECT
    MIN(n.negotiation_start_date) AS min_negotiation_start_date,
    MAX(n.negotiation_start_date) AS max_negotiation_start_date,
    MIN(n.negotiation_end_date) AS min_negotiation_end_date,
    MAX(n.negotiation_end_date) AS max_negotiation_end_date,
    MIN(n.anticipated_award_date) AS min_anticipated_award_date,
    MAX(n.anticipated_award_date) AS max_anticipated_award_date,
    MIN(n.update_timestamp) AS min_update_timestamp,
    MAX(n.update_timestamp) AS max_update_timestamp
FROM KCOEUS.NEGOTIATION n;

-- 9. NULL percentages and ASSOCIATED_DOCUMENT_ID population.
SELECT
    ROUND(100 * SUM(CASE WHEN n.document_number IS NULL THEN 1 ELSE 0 END)
        / NULLIF(COUNT(*), 0), 2) AS document_number_null_pct,
    ROUND(100 * SUM(CASE WHEN n.negotation_status_id IS NULL THEN 1 ELSE 0 END)
        / NULLIF(COUNT(*), 0), 2) AS status_id_null_pct,
    ROUND(100 * SUM(CASE WHEN n.negotiation_agreement_type_id IS NULL THEN 1 ELSE 0 END)
        / NULLIF(COUNT(*), 0), 2) AS agreement_type_id_null_pct,
    ROUND(100 * SUM(CASE WHEN n.negotiation_assc_type_id IS NULL THEN 1 ELSE 0 END)
        / NULLIF(COUNT(*), 0), 2) AS association_type_id_null_pct,
    ROUND(100 * SUM(CASE WHEN n.associated_document_id IS NULL THEN 1 ELSE 0 END)
        / NULLIF(COUNT(*), 0), 2) AS associated_document_id_null_pct,
    SUM(CASE WHEN n.associated_document_id IS NOT NULL THEN 1 ELSE 0 END)
        AS associated_document_id_populated_count,
    ROUND(100 * SUM(CASE WHEN n.update_timestamp IS NULL THEN 1 ELSE 0 END)
        / NULLIF(COUNT(*), 0), 2) AS update_timestamp_null_pct,
    ROUND(100 * SUM(CASE WHEN n.update_user IS NULL THEN 1 ELSE 0 END)
        / NULLIF(COUNT(*), 0), 2) AS update_user_null_pct,
    ROUND(100 * SUM(CASE WHEN n.ver_nbr IS NULL THEN 1 ELSE 0 END)
        / NULLIF(COUNT(*), 0), 2) AS ver_nbr_null_pct,
    ROUND(100 * SUM(CASE WHEN n.obj_id IS NULL THEN 1 ELSE 0 END)
        / NULLIF(COUNT(*), 0), 2) AS obj_id_null_pct
FROM KCOEUS.NEGOTIATION n;

-- ============================================================================
-- activities
-- ============================================================================

-- 1-4. Total rows, distinct primary keys, duplicate-key surplus, NULL keys.
SELECT
    COUNT(*) AS total_row_count,
    COUNT(DISTINCT a.negotiation_activity_id) AS distinct_primary_key_count,
    COUNT(*) - COUNT(DISTINCT a.negotiation_activity_id)
        AS duplicate_primary_key_rows,
    SUM(CASE WHEN a.negotiation_activity_id IS NULL THEN 1 ELSE 0 END)
        AS null_primary_keys
FROM KCOEUS.NEGOTIATION_ACTIVITY a;

-- 3. Duplicate primary-key values (expect no rows).
SELECT a.negotiation_activity_id, COUNT(*) AS duplicate_count
FROM KCOEUS.NEGOTIATION_ACTIVITY a
GROUP BY a.negotiation_activity_id
HAVING COUNT(*) > 1
ORDER BY a.negotiation_activity_id;

-- 5. Orphan NEGOTIATION_ID values (expect zero).
SELECT COUNT(*) AS orphan_negotiation_id_count
FROM KCOEUS.NEGOTIATION_ACTIVITY a
LEFT JOIN KCOEUS.NEGOTIATION n
    ON n.negotiation_id = a.negotiation_id
WHERE a.negotiation_id IS NOT NULL
  AND n.negotiation_id IS NULL;

-- 6. Missing activity-type/location rows and descriptions.
SELECT
    SUM(CASE WHEN a.activity_type_id IS NOT NULL
                  AND at.negotiation_activity_type_id IS NULL THEN 1 ELSE 0 END)
        AS missing_activity_type_lookup_count,
    SUM(CASE WHEN at.negotiation_activity_type_id IS NOT NULL
                  AND at.description IS NULL THEN 1 ELSE 0 END)
        AS null_activity_type_description_count,
    SUM(CASE WHEN a.location_id IS NOT NULL
                  AND nl.negotiation_location_id IS NULL THEN 1 ELSE 0 END)
        AS missing_location_lookup_count,
    SUM(CASE WHEN nl.negotiation_location_id IS NOT NULL
                  AND nl.description IS NULL THEN 1 ELSE 0 END)
        AS null_location_description_count
FROM KCOEUS.NEGOTIATION_ACTIVITY a
LEFT JOIN KCOEUS.NEGOTIATION_ACTIVITY_TYPE at
    ON at.negotiation_activity_type_id = a.activity_type_id
LEFT JOIN KCOEUS.NEGOTIATION_LOCATION nl
    ON nl.negotiation_location_id = a.location_id;

-- 7. Sample 10 export rows.
SELECT *
FROM (
    SELECT
        a.negotiation_activity_id,
        a.negotiation_id,
        a.activity_type_id,
        at.description AS activity_type_description,
        a.location_id,
        nl.description AS location_description,
        a.start_date,
        a.end_date,
        a.followup_date,
        a.description,
        a.update_timestamp,
        a.update_user,
        a.ver_nbr,
        a.obj_id
    FROM KCOEUS.NEGOTIATION_ACTIVITY a
    LEFT JOIN KCOEUS.NEGOTIATION_ACTIVITY_TYPE at
        ON at.negotiation_activity_type_id = a.activity_type_id
    LEFT JOIN KCOEUS.NEGOTIATION_LOCATION nl
        ON nl.negotiation_location_id = a.location_id
    ORDER BY a.negotiation_id, a.negotiation_activity_id
)
WHERE ROWNUM <= 10;

-- 8. Date and audit timestamp ranges.
SELECT
    MIN(a.start_date) AS min_start_date,
    MAX(a.start_date) AS max_start_date,
    MIN(a.end_date) AS min_end_date,
    MAX(a.end_date) AS max_end_date,
    MIN(a.create_date) AS min_create_date,
    MAX(a.create_date) AS max_create_date,
    MIN(a.followup_date) AS min_followup_date,
    MAX(a.followup_date) AS max_followup_date,
    MIN(a.last_modified_date) AS min_last_modified_date,
    MAX(a.last_modified_date) AS max_last_modified_date,
    MIN(a.update_timestamp) AS min_update_timestamp,
    MAX(a.update_timestamp) AS max_update_timestamp
FROM KCOEUS.NEGOTIATION_ACTIVITY a;

-- 9. NULL percentages for relationship, lookup, date, and audit columns.
SELECT
    ROUND(100 * SUM(CASE WHEN a.negotiation_id IS NULL THEN 1 ELSE 0 END)
        / NULLIF(COUNT(*), 0), 2) AS negotiation_id_null_pct,
    ROUND(100 * SUM(CASE WHEN a.activity_type_id IS NULL THEN 1 ELSE 0 END)
        / NULLIF(COUNT(*), 0), 2) AS activity_type_id_null_pct,
    ROUND(100 * SUM(CASE WHEN a.location_id IS NULL THEN 1 ELSE 0 END)
        / NULLIF(COUNT(*), 0), 2) AS location_id_null_pct,
    ROUND(100 * SUM(CASE WHEN a.start_date IS NULL THEN 1 ELSE 0 END)
        / NULLIF(COUNT(*), 0), 2) AS start_date_null_pct,
    ROUND(100 * SUM(CASE WHEN a.description IS NULL THEN 1 ELSE 0 END)
        / NULLIF(COUNT(*), 0), 2) AS description_null_pct,
    ROUND(100 * SUM(CASE WHEN a.update_timestamp IS NULL THEN 1 ELSE 0 END)
        / NULLIF(COUNT(*), 0), 2) AS update_timestamp_null_pct,
    ROUND(100 * SUM(CASE WHEN a.update_user IS NULL THEN 1 ELSE 0 END)
        / NULLIF(COUNT(*), 0), 2) AS update_user_null_pct,
    ROUND(100 * SUM(CASE WHEN a.ver_nbr IS NULL THEN 1 ELSE 0 END)
        / NULLIF(COUNT(*), 0), 2) AS ver_nbr_null_pct,
    ROUND(100 * SUM(CASE WHEN a.obj_id IS NULL THEN 1 ELSE 0 END)
        / NULLIF(COUNT(*), 0), 2) AS obj_id_null_pct
FROM KCOEUS.NEGOTIATION_ACTIVITY a;

-- ============================================================================
-- custom data
-- ============================================================================

-- 1-4. Total rows, distinct primary keys, duplicate-key surplus, NULL keys.
SELECT
    COUNT(*) AS total_row_count,
    COUNT(DISTINCT cd.negotiation_custom_data_id)
        AS distinct_primary_key_count,
    COUNT(*) - COUNT(DISTINCT cd.negotiation_custom_data_id)
        AS duplicate_primary_key_rows,
    SUM(CASE WHEN cd.negotiation_custom_data_id IS NULL THEN 1 ELSE 0 END)
        AS null_primary_keys
FROM KCOEUS.NEGOTIATION_CUSTOM_DATA cd;

-- 3. Duplicate primary-key values (expect no rows).
SELECT cd.negotiation_custom_data_id, COUNT(*) AS duplicate_count
FROM KCOEUS.NEGOTIATION_CUSTOM_DATA cd
GROUP BY cd.negotiation_custom_data_id
HAVING COUNT(*) > 1
ORDER BY cd.negotiation_custom_data_id;

-- 5. Orphan NEGOTIATION_ID values (expect zero).
SELECT COUNT(*) AS orphan_negotiation_id_count
FROM KCOEUS.NEGOTIATION_CUSTOM_DATA cd
LEFT JOIN KCOEUS.NEGOTIATION n
    ON n.negotiation_id = cd.negotiation_id
WHERE cd.negotiation_id IS NOT NULL
  AND n.negotiation_id IS NULL;

-- 6. CUSTOM_ATTRIBUTE_ID coverage. No verified lookup object exists yet, so
-- this reports missing source IDs rather than a speculative lookup join.
SELECT
    SUM(CASE WHEN cd.custom_attribute_id IS NULL THEN 1 ELSE 0 END)
        AS null_custom_attribute_id_count,
    COUNT(DISTINCT cd.custom_attribute_id) AS distinct_custom_attribute_id_count
FROM KCOEUS.NEGOTIATION_CUSTOM_DATA cd;

-- 7. Sample 10 export rows.
SELECT *
FROM (
    SELECT
        cd.negotiation_custom_data_id,
        cd.negotiation_id,
        cd.negotiation_number,
        cd.custom_attribute_id,
        cd.value,
        cd.update_timestamp,
        cd.update_user,
        cd.ver_nbr,
        cd.obj_id
    FROM KCOEUS.NEGOTIATION_CUSTOM_DATA cd
    ORDER BY cd.negotiation_id, cd.negotiation_custom_data_id
)
WHERE ROWNUM <= 10;

-- 8. Audit timestamp range (the entity has no business date column).
SELECT
    MIN(cd.update_timestamp) AS min_update_timestamp,
    MAX(cd.update_timestamp) AS max_update_timestamp
FROM KCOEUS.NEGOTIATION_CUSTOM_DATA cd;

-- 9. NULL percentages for relationship, value, and audit columns.
SELECT
    ROUND(100 * SUM(CASE WHEN cd.negotiation_id IS NULL THEN 1 ELSE 0 END)
        / NULLIF(COUNT(*), 0), 2) AS negotiation_id_null_pct,
    ROUND(100 * SUM(CASE WHEN cd.negotiation_number IS NULL THEN 1 ELSE 0 END)
        / NULLIF(COUNT(*), 0), 2) AS negotiation_number_null_pct,
    ROUND(100 * SUM(CASE WHEN cd.custom_attribute_id IS NULL THEN 1 ELSE 0 END)
        / NULLIF(COUNT(*), 0), 2) AS custom_attribute_id_null_pct,
    ROUND(100 * SUM(CASE WHEN cd.value IS NULL THEN 1 ELSE 0 END)
        / NULLIF(COUNT(*), 0), 2) AS value_null_pct,
    ROUND(100 * SUM(CASE WHEN cd.update_timestamp IS NULL THEN 1 ELSE 0 END)
        / NULLIF(COUNT(*), 0), 2) AS update_timestamp_null_pct,
    ROUND(100 * SUM(CASE WHEN cd.update_user IS NULL THEN 1 ELSE 0 END)
        / NULLIF(COUNT(*), 0), 2) AS update_user_null_pct,
    ROUND(100 * SUM(CASE WHEN cd.ver_nbr IS NULL THEN 1 ELSE 0 END)
        / NULLIF(COUNT(*), 0), 2) AS ver_nbr_null_pct,
    ROUND(100 * SUM(CASE WHEN cd.obj_id IS NULL THEN 1 ELSE 0 END)
        / NULLIF(COUNT(*), 0), 2) AS obj_id_null_pct
FROM KCOEUS.NEGOTIATION_CUSTOM_DATA cd;

-- ============================================================================
-- notifications
-- ============================================================================

-- 1-4. Total rows, distinct primary keys, duplicate-key surplus, NULL keys.
SELECT
    COUNT(*) AS total_row_count,
    COUNT(DISTINCT nn.notification_id) AS distinct_primary_key_count,
    COUNT(*) - COUNT(DISTINCT nn.notification_id) AS duplicate_primary_key_rows,
    SUM(CASE WHEN nn.notification_id IS NULL THEN 1 ELSE 0 END)
        AS null_primary_keys
FROM KCOEUS.NEGOTIATION_NOTIFICATION nn;

-- 3. Duplicate primary-key values (expect no rows).
SELECT nn.notification_id, COUNT(*) AS duplicate_count
FROM KCOEUS.NEGOTIATION_NOTIFICATION nn
GROUP BY nn.notification_id
HAVING COUNT(*) > 1
ORDER BY nn.notification_id;

-- 5. Orphan OWNING_DOCUMENT_ID_FK values (descriptor maps this to
-- NEGOTIATION.NEGOTIATION_ID; expect zero after relationship validation).
SELECT COUNT(*) AS orphan_owning_document_id_count
FROM KCOEUS.NEGOTIATION_NOTIFICATION nn
LEFT JOIN KCOEUS.NEGOTIATION n
    ON n.negotiation_id = nn.owning_document_id_fk
WHERE nn.owning_document_id_fk IS NOT NULL
  AND n.negotiation_id IS NULL;

-- Additional DOCUMENT_NUMBER orphan check against NEGOTIATION_DOCUMENT.
SELECT COUNT(*) AS orphan_document_number_count
FROM KCOEUS.NEGOTIATION_NOTIFICATION nn
LEFT JOIN KCOEUS.NEGOTIATION_DOCUMENT nd
    ON nd.document_number = nn.document_number
WHERE nn.document_number IS NOT NULL
  AND nd.document_number IS NULL;

-- 6. NOTIFICATION_TYPE_ID coverage. The common lookup table is unverified,
-- so this reports missing source IDs rather than a speculative lookup join.
SELECT
    SUM(CASE WHEN nn.notification_type_id IS NULL THEN 1 ELSE 0 END)
        AS null_notification_type_id_count,
    COUNT(DISTINCT nn.notification_type_id) AS distinct_notification_type_count
FROM KCOEUS.NEGOTIATION_NOTIFICATION nn;

-- 7. Sample 10 export rows.
SELECT *
FROM (
    SELECT
        nn.notification_id,
        nn.notification_type_id,
        nn.document_number,
        nn.owning_document_id_fk,
        nn.recipients,
        nn.subject,
        nn.message,
        nn.update_timestamp,
        nn.update_user,
        nn.ver_nbr,
        nn.obj_id
    FROM KCOEUS.NEGOTIATION_NOTIFICATION nn
    ORDER BY nn.owning_document_id_fk, nn.notification_id
)
WHERE ROWNUM <= 10;

-- 8. Audit timestamp range (the entity has no business date column).
SELECT
    MIN(nn.update_timestamp) AS min_update_timestamp,
    MAX(nn.update_timestamp) AS max_update_timestamp
FROM KCOEUS.NEGOTIATION_NOTIFICATION nn;

-- 9. NULL percentages for relationship, content, and audit columns.
SELECT
    ROUND(100 * SUM(CASE WHEN nn.notification_type_id IS NULL THEN 1 ELSE 0 END)
        / NULLIF(COUNT(*), 0), 2) AS notification_type_id_null_pct,
    ROUND(100 * SUM(CASE WHEN nn.document_number IS NULL THEN 1 ELSE 0 END)
        / NULLIF(COUNT(*), 0), 2) AS document_number_null_pct,
    ROUND(100 * SUM(CASE WHEN nn.owning_document_id_fk IS NULL THEN 1 ELSE 0 END)
        / NULLIF(COUNT(*), 0), 2) AS owning_document_id_fk_null_pct,
    ROUND(100 * SUM(CASE WHEN nn.recipients IS NULL THEN 1 ELSE 0 END)
        / NULLIF(COUNT(*), 0), 2) AS recipients_null_pct,
    ROUND(100 * SUM(CASE WHEN nn.subject IS NULL THEN 1 ELSE 0 END)
        / NULLIF(COUNT(*), 0), 2) AS subject_null_pct,
    ROUND(100 * SUM(CASE WHEN nn.message IS NULL THEN 1 ELSE 0 END)
        / NULLIF(COUNT(*), 0), 2) AS message_null_pct,
    ROUND(100 * SUM(CASE WHEN nn.update_timestamp IS NULL THEN 1 ELSE 0 END)
        / NULLIF(COUNT(*), 0), 2) AS update_timestamp_null_pct,
    ROUND(100 * SUM(CASE WHEN nn.update_user IS NULL THEN 1 ELSE 0 END)
        / NULLIF(COUNT(*), 0), 2) AS update_user_null_pct,
    ROUND(100 * SUM(CASE WHEN nn.ver_nbr IS NULL THEN 1 ELSE 0 END)
        / NULLIF(COUNT(*), 0), 2) AS ver_nbr_null_pct,
    ROUND(100 * SUM(CASE WHEN nn.obj_id IS NULL THEN 1 ELSE 0 END)
        / NULLIF(COUNT(*), 0), 2) AS obj_id_null_pct
FROM KCOEUS.NEGOTIATION_NOTIFICATION nn;

-- ============================================================================
-- unassociated
-- ============================================================================

-- 1-4. Total rows, distinct primary keys, duplicate-key surplus, NULL keys.
SELECT
    COUNT(*) AS total_row_count,
    COUNT(DISTINCT ud.negotiation_unassoc_detail_id)
        AS distinct_primary_key_count,
    COUNT(*) - COUNT(DISTINCT ud.negotiation_unassoc_detail_id)
        AS duplicate_primary_key_rows,
    SUM(CASE WHEN ud.negotiation_unassoc_detail_id IS NULL THEN 1 ELSE 0 END)
        AS null_primary_keys
FROM KCOEUS.NEGOTIATION_UNASSOC_DETAIL ud;

-- 3. Duplicate primary-key values (expect no rows).
SELECT ud.negotiation_unassoc_detail_id, COUNT(*) AS duplicate_count
FROM KCOEUS.NEGOTIATION_UNASSOC_DETAIL ud
GROUP BY ud.negotiation_unassoc_detail_id
HAVING COUNT(*) > 1
ORDER BY ud.negotiation_unassoc_detail_id;

-- 5. Orphan NEGOTIATION_ID values (expect zero).
SELECT COUNT(*) AS orphan_negotiation_id_count
FROM KCOEUS.NEGOTIATION_UNASSOC_DETAIL ud
LEFT JOIN KCOEUS.NEGOTIATION n
    ON n.negotiation_id = ud.negotiation_id
WHERE ud.negotiation_id IS NOT NULL
  AND n.negotiation_id IS NULL;

-- 6. Referenced lookup identifiers. Unit, sponsor, organization, and Rolodex
-- lookup tables are not in the verified object set, so no speculative joins
-- are made. These counts identify which references need later lookup coverage.
SELECT
    SUM(CASE WHEN ud.lead_unit IS NULL THEN 1 ELSE 0 END) AS null_lead_unit_count,
    COUNT(DISTINCT ud.lead_unit) AS distinct_lead_unit_count,
    SUM(CASE WHEN ud.sponsor_code IS NULL THEN 1 ELSE 0 END)
        AS null_sponsor_code_count,
    COUNT(DISTINCT ud.sponsor_code) AS distinct_sponsor_code_count,
    SUM(CASE WHEN ud.prime_sponsor_code IS NULL THEN 1 ELSE 0 END)
        AS null_prime_sponsor_code_count,
    COUNT(DISTINCT ud.prime_sponsor_code) AS distinct_prime_sponsor_code_count,
    SUM(CASE WHEN ud.subaward_org IS NULL THEN 1 ELSE 0 END)
        AS null_subaward_org_count,
    COUNT(DISTINCT ud.subaward_org) AS distinct_subaward_org_count
FROM KCOEUS.NEGOTIATION_UNASSOC_DETAIL ud;

-- 7. Sample 10 export rows.
SELECT *
FROM (
    SELECT
        ud.negotiation_unassoc_detail_id,
        ud.negotiation_id,
        ud.title,
        ud.pi_person_id,
        ud.pi_rolodex_id,
        ud.lead_unit,
        ud.sponsor_code,
        ud.pi_name,
        ud.prime_sponsor_code,
        ud.sponsor_award_number,
        ud.contact_admin_person_id,
        ud.subaward_org,
        ud.update_timestamp,
        ud.update_user,
        ud.ver_nbr,
        ud.obj_id
    FROM KCOEUS.NEGOTIATION_UNASSOC_DETAIL ud
    ORDER BY ud.negotiation_id, ud.negotiation_unassoc_detail_id
)
WHERE ROWNUM <= 10;

-- 8. Audit timestamp range (the entity has no business date column).
SELECT
    MIN(ud.update_timestamp) AS min_update_timestamp,
    MAX(ud.update_timestamp) AS max_update_timestamp
FROM KCOEUS.NEGOTIATION_UNASSOC_DETAIL ud;

-- 9. NULL percentages for relationship, business, and audit columns.
SELECT
    ROUND(100 * SUM(CASE WHEN ud.negotiation_id IS NULL THEN 1 ELSE 0 END)
        / NULLIF(COUNT(*), 0), 2) AS negotiation_id_null_pct,
    ROUND(100 * SUM(CASE WHEN ud.title IS NULL THEN 1 ELSE 0 END)
        / NULLIF(COUNT(*), 0), 2) AS title_null_pct,
    ROUND(100 * SUM(CASE WHEN ud.pi_person_id IS NULL
                              AND ud.pi_rolodex_id IS NULL THEN 1 ELSE 0 END)
        / NULLIF(COUNT(*), 0), 2) AS all_pi_identifier_null_pct,
    ROUND(100 * SUM(CASE WHEN ud.lead_unit IS NULL THEN 1 ELSE 0 END)
        / NULLIF(COUNT(*), 0), 2) AS lead_unit_null_pct,
    ROUND(100 * SUM(CASE WHEN ud.sponsor_code IS NULL THEN 1 ELSE 0 END)
        / NULLIF(COUNT(*), 0), 2) AS sponsor_code_null_pct,
    ROUND(100 * SUM(CASE WHEN ud.update_timestamp IS NULL THEN 1 ELSE 0 END)
        / NULLIF(COUNT(*), 0), 2) AS update_timestamp_null_pct,
    ROUND(100 * SUM(CASE WHEN ud.update_user IS NULL THEN 1 ELSE 0 END)
        / NULLIF(COUNT(*), 0), 2) AS update_user_null_pct,
    ROUND(100 * SUM(CASE WHEN ud.ver_nbr IS NULL THEN 1 ELSE 0 END)
        / NULLIF(COUNT(*), 0), 2) AS ver_nbr_null_pct,
    ROUND(100 * SUM(CASE WHEN ud.obj_id IS NULL THEN 1 ELSE 0 END)
        / NULLIF(COUNT(*), 0), 2) AS obj_id_null_pct
FROM KCOEUS.NEGOTIATION_UNASSOC_DETAIL ud;

-- ============================================================================
-- Known constraints and indexes
-- ============================================================================

-- Confirm actual primary/unique/foreign-key constraints for every export and
-- lookup table. The descriptor identifies business keys, but this query is the
-- authority for constraints installed in BU Oracle.
SELECT
    ac.owner,
    ac.table_name,
    ac.constraint_name,
    ac.constraint_type,
    acc.position,
    acc.column_name,
    ac.status
FROM ALL_CONSTRAINTS ac
JOIN ALL_CONS_COLUMNS acc
    ON acc.owner = ac.owner
   AND acc.constraint_name = ac.constraint_name
   AND acc.table_name = ac.table_name
WHERE ac.owner = 'KCOEUS'
  AND ac.table_name IN (
      'NEGOTIATION',
      'NEGOTIATION_DOCUMENT',
      'NEGOTIATION_STATUS',
      'NEGOTIATION_AGREEMENT_TYPE',
      'NEGOTIATION_ASSOCIATION_TYPE',
      'NEGOTIATION_ACTIVITY',
      'NEGOTIATION_ACTIVITY_TYPE',
      'NEGOTIATION_LOCATION',
      'NEGOTIATION_CUSTOM_DATA',
      'NEGOTIATION_NOTIFICATION',
      'NEGOTIATION_UNASSOC_DETAIL'
  )
ORDER BY ac.table_name, ac.constraint_name, acc.position;

-- Confirm indexes supporting primary keys and relationship/lookup joins.
SELECT
    ai.table_owner AS owner,
    ai.table_name,
    ai.index_name,
    ai.uniqueness,
    aic.column_position,
    aic.column_name,
    ai.status
FROM ALL_INDEXES ai
JOIN ALL_IND_COLUMNS aic
    ON aic.index_owner = ai.owner
   AND aic.index_name = ai.index_name
   AND aic.table_owner = ai.table_owner
   AND aic.table_name = ai.table_name
WHERE ai.table_owner = 'KCOEUS'
  AND ai.table_name IN (
      'NEGOTIATION',
      'NEGOTIATION_DOCUMENT',
      'NEGOTIATION_STATUS',
      'NEGOTIATION_AGREEMENT_TYPE',
      'NEGOTIATION_ASSOCIATION_TYPE',
      'NEGOTIATION_ACTIVITY',
      'NEGOTIATION_ACTIVITY_TYPE',
      'NEGOTIATION_LOCATION',
      'NEGOTIATION_CUSTOM_DATA',
      'NEGOTIATION_NOTIFICATION',
      'NEGOTIATION_UNASSOC_DETAIL'
  )
ORDER BY ai.table_name, ai.index_name, aic.column_position;
