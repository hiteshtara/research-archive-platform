-- Export source Award associations. AWARD_ID is retained without enrichment
-- because the Award table is outside the verified Subaward inventory.
SELECT
    sfs.subaward_funding_source_id AS subaward_funding_source_id,
    sfs.subaward_id AS subaward_id,
    sfs.subaward_code AS subaward_code,
    sfs.sequence_number AS sequence_number,
    sfs.award_id AS award_id,
    sfs.update_timestamp AS update_timestamp,
    sfs.update_user AS update_user,
    sfs.ver_nbr AS ver_nbr,
    sfs.obj_id AS obj_id
FROM KCOEUS.SUBAWARD_FUNDING_SOURCE sfs
ORDER BY sfs.subaward_code, sfs.sequence_number,
    sfs.subaward_funding_source_id;
