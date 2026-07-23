-- Export every physical Subaward version with its document, BU extension,
-- and verified status description. SUBAWARD_CODE groups the version family;
-- SUBAWARD_ID identifies the physical source row.
SELECT
    s.subaward_id AS subaward_id,
    s.document_number AS document_number,
    s.sequence_number AS sequence_number,
    s.subaward_code AS subaward_code,
    s.organization_id AS organization_id,
    s.start_date AS start_date,
    s.end_date AS end_date,
    s.subaward_type_code AS subaward_type_code,
    s.purchase_order_num AS purchase_order_num,
    s.title AS title,
    s.status_code AS status_code,
    ss.description AS status_description,
    s.account_number AS account_number,
    s.vendor_number AS vendor_number,
    s.requisitioner_id AS requisitioner_id,
    s.requisitioner_unit AS requisitioner_unit,
    s.archive_location AS archive_location,
    s.closeout_date AS closeout_date,
    s.comments AS comments,
    s.site_investigator AS site_investigator,
    s.cost_type AS cost_type,
    s.date_of_fully_executed AS date_of_fully_executed,
    s.requisition_number AS requisition_number,
    s.fed_award_proj_desc AS fed_award_proj_desc,
    s.f_and_a_rate AS f_and_a_rate,
    s.de_minimus AS de_minimus,
    s.subaward_sequence_status AS subaward_sequence_status,
    s.ffata_required AS ffata_required,
    s.fsrs_subaward_number AS fsrs_subaward_number,
    s.award_prime_sponsor_name AS award_prime_sponsor_name,
    s.award_sponsor_name AS award_sponsor_name,
    se.date_received AS extension_date_received,
    s.update_timestamp AS update_timestamp,
    s.update_user AS update_user,
    s.ver_nbr AS ver_nbr,
    s.obj_id AS obj_id,
    sd.update_timestamp AS document_update_timestamp,
    sd.update_user AS document_update_user,
    sd.ver_nbr AS document_ver_nbr,
    sd.obj_id AS document_obj_id
FROM KCOEUS.SUBAWARD s
LEFT JOIN KCOEUS.SUBAWARD_STATUS ss
    ON ss.subaward_status_code = s.status_code
LEFT JOIN KCOEUS.SUBAWARD_EXTENSION se
    ON se.subaward_id = s.subaward_id
LEFT JOIN KCOEUS.SUBAWARD_DOCUMENT sd
    ON sd.document_number = s.document_number
ORDER BY s.subaward_code, s.sequence_number, s.subaward_id;
