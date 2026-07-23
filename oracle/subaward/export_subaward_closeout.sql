-- Export Subaward closeout milestones. CLOSEOUT_TYPE_CODE is retained without
-- enrichment because CLOSEOUT_TYPE is absent from the verified inventory.
SELECT
    sc.subaward_closeout_id AS subaward_closeout_id,
    sc.subaward_id AS subaward_id,
    sc.subaward_code AS subaward_code,
    sc.sequence_number AS sequence_number,
    sc.closeout_number AS closeout_number,
    sc.closeout_type_code AS closeout_type_code,
    sc.date_requested AS date_requested,
    sc.date_followup AS date_followup,
    sc.date_received AS date_received,
    sc.comments AS comments,
    sc.update_timestamp AS update_timestamp,
    sc.update_user AS update_user,
    sc.ver_nbr AS ver_nbr,
    sc.obj_id AS obj_id
FROM KCOEUS.SUBAWARD_CLOSEOUT sc
ORDER BY sc.subaward_code, sc.sequence_number, sc.subaward_closeout_id;
